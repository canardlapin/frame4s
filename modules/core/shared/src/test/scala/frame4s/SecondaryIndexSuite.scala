package frame4s

class SecondaryIndexSuite extends munit.FunSuite:
  type Row = (id: Option[Int], value: Int)

  private val layouts = SecondaryIndexLayout.values.toVector

  private def storage[A](result: Either[StorageError, A]): A =
    result.fold(error => fail(error.message), identity)

  private def frame[A](result: Either[FrameError, A]): A =
    result.fold(error => fail(error.message), identity)

  private def fixture(
      ids: Array[Int],
      valid: Array[Boolean]
  ): (Table[Row], SourceRef, ReferenceSources) =
    fixture(Vector(ids), Vector(valid), "secondary-index-test")

  private def fixture(
      idBatches: Vector[Array[Int]],
      validBatches: Vector[Array[Boolean]],
      name: String
  ): (Table[Row], SourceRef, ReferenceSources) =
    val schema = summon[SchemaDescriptor[Row]].schema
    var ordinal = 0
    val table = storage:
      Table.takeOwnership[Row](
        idBatches
          .zip(validBatches)
          .map: (ids, valid) =>
            val values = Array.tabulate(ids.length): row =>
              val value = ordinal + row
              value
            ordinal += ids.length
            storage:
              RecordBatch(
                schema,
                Vector(
                  storage(ColumnArray.int32(ids, valid)),
                  storage(ColumnArray.int32(values))
                )
              )
      )
    val reference = frame(SourceRef.values(name, name))
    (table, reference, ReferenceSources.empty.bind(reference, table))

  test("lookup preserves source order, deduplicates query keys, and excludes nulls"):
    val ids = Array(4, 1, 4, 2, 1, 3, 4)
    val valid = Array(true, true, false, true, true, false, true)
    val (table, reference, sources) = fixture(ids, valid)
    try
      layouts.foreach: layout =>
        val index = build(sources, reference, table.schema, layout)
        try
          val selection = lookup(index, Array(4, 1, 4))
          assertEquals(selection.size, 4, layout.label)
          assertEquals(ordinals(selection), Vector(0, 1, 4, 6), layout.label)
          assertEquals(
            ordinals(selection).map(ids),
            Vector(4, 1, 1, 4),
            layout.label
          )
          assertEquals(lookup(index, 3).size, 0, layout.label)
        finally index.close()
    finally table.close()

  test("single-key backend traversal preserves source ordinals for every layout"):
    val ids = Array(9, 4, 9, 1, 4, 9)
    val (table, reference, sources) = fixture(ids, Array.fill(ids.length)(true))
    try
      layouts.foreach: layout =>
        val index = build(sources, reference, table.schema, layout)
        try
          val traversed = index
            .withView(sources, reference, table.schema, 0): view =>
              val builder = Vector.newBuilder[Int]
              var token = view.firstUnsafe(9)
              while token >= 0 do
                builder += view.rowUnsafe(token)
                token = view.nextUnsafe(token)
              builder.result()
            .fold(error => fail(error.message), identity)
          assertEquals(traversed, Vector(0, 2, 5), layout.label)
        finally index.close()
    finally table.close()

  test("index is bound to one exact ReferenceSources value"):
    val ids = Array(2, 1, 0)
    val (table, reference, sources) = fixture(ids, Array.fill(ids.length)(true))
    val otherSources = ReferenceSources.empty.bind(reference, table)
    try
      layouts.foreach: layout =>
        val index = build(sources, reference, table.schema, layout)
        try
          assertEquals(
            index.validateBinding(sources, reference, table.schema, 0),
            Right(()),
            layout.label
          )
          assertEquals(
            index.validateBinding(otherSources, reference, table.schema, 0),
            Left(SecondaryIndexError.SourceMismatch),
            layout.label
          )
        finally index.close()
    finally table.close()

  test("close invalidates index lookup while detached selections remain bounds checked"):
    val ids = Array(0, 1, 2)
    val (table, reference, sources) = fixture(ids, Array.fill(ids.length)(true))
    try
      layouts.foreach: layout =>
        val index = build(sources, reference, table.schema, layout)
        val selection = lookup(index, 1)
        index.close()
        assert(index.isClosed, layout.label)
        assertEquals(index.lookup(1), Left(SecondaryIndexError.Closed), layout.label)
        assertEquals(selection.rowOrdinal(0), Right(1), layout.label)
        assertEquals(
          selection.rowOrdinal(1),
          Left(SecondaryIndexError.InvalidRowOrdinal(1, 1)),
          layout.label
        )
    finally table.close()

  test("sorted layouts report exact owned arrays including packed ordinal bits"):
    val ids = Array(4, 1, 4, 2, 1, 3, 4)
    val valid = Array(true, true, false, true, true, false, true)
    val (table, reference, sources) = fixture(ids, valid)
    val compact =
      build(sources, reference, table.schema, SecondaryIndexLayout.CompactSorted)
    val packed =
      build(sources, reference, table.schema, SecondaryIndexLayout.PackedSorted)
    val fast = build(sources, reference, table.schema, SecondaryIndexLayout.FastHash)
    val grouped =
      build(sources, reference, table.schema, SecondaryIndexLayout.GroupedHash)
    try
      assertEquals(compact.rowCount, ids.length)
      assertEquals(compact.ownedBytes, 5L * 8L)
      assertEquals(packed.ownedBytes, 5L * 4L + 4L)
      assertEquals(grouped.ownedBytes, 152L)
      assertEquals(
        Int32OrdinalStore.ownedBytesFor(1000000, 1000000),
        2500000L
      )
      assertEquals(1000000L * 4L + 2500000L, 6500000L)
      assert(packed.ownedBytes < compact.ownedBytes)
      assert(compact.ownedBytes < fast.ownedBytes)
    finally
      compact.close()
      packed.close()
      fast.close()
      grouped.close()
      table.close()

  test("empty and all-null sources have sound memory-oriented lookup and ownership"):
    Vector(
      Array.emptyIntArray -> Array.emptyBooleanArray,
      Array(1, 2, 3) -> Array(false, false, false)
    ).zipWithIndex.foreach: item =>
      val ((ids, valid), fixtureIndex) = item
      val (table, reference, sources) =
        fixture(ids, valid)
      try
        Vector(
          SecondaryIndexLayout.CompactSorted,
          SecondaryIndexLayout.PackedSorted,
          SecondaryIndexLayout.FlatHashRows
        ).foreach: layout =>
          val index = build(sources, reference, table.schema, layout)
          try
            assertEquals(index.ownedBytes, 0L, s"fixture $fixtureIndex ${layout.label}")
            assertEquals(
              lookup(index, Array(Int.MinValue, 0, Int.MaxValue)).size,
              0,
              layout.label
            )
          finally index.close()
      finally table.close()

  test("packed ordinals round-trip across word boundaries and batch partitions"):
    val size = 97
    val ids = Array.tabulate(size): row =>
      if row % 9 == 0 then Int.MinValue
      else if row % 11 == 0 then Int.MaxValue
      else (row * 37) % 13
    val valid = Array.tabulate(size)(_ % 7 != 0)
    val (table, reference, sources) =
      fixture(
        Vector(ids.take(31), ids.slice(31, 65), ids.drop(65)),
        Vector(valid.take(31), valid.slice(31, 65), valid.drop(65)),
        "secondary-index-packed-boundaries"
      )
    val keys = Array(Int.MinValue, Int.MaxValue, 0, 3, 8, 12)
    val expected = ids.indices.filter: row =>
      valid(row) && keys.contains(ids(row))
    val index =
      build(sources, reference, table.schema, SecondaryIndexLayout.PackedSorted)
    try
      assertEquals(ordinals(lookup(index, keys)), expected.toVector)
      keys.foreach: key =>
        assertEquals(
          ordinals(lookup(index, key)),
          ids.indices.filter(row => valid(row) && ids(row) == key).toVector
        )
    finally
      index.close()
      table.close()

  test("flat row hash preserves source order through collisions and wrap-around"):
    assert(Int32SecondaryIndex.MaxRows < (1L << 30))
    val capacity = Int32SecondaryIndex.flatCapacityFor(12)
    assertEquals(capacity, 16)
    val buckets = Array.fill(capacity)(Vector.empty[Int])
    var candidate = -10000
    while candidate <= 10000 do
      val slot = SecondaryIndexHash.initialSlot(candidate, capacity)
      if slot >= capacity - 3 && buckets(slot).length < 4 then
        buckets(slot) = buckets(slot) :+ candidate
      candidate += 1
    val collisionKeys =
      buckets.find(_.length == 4).getOrElse(fail("could not construct wrap fixture"))
    val ids = Array(
      collisionKeys(0),
      collisionKeys(1),
      collisionKeys(0),
      collisionKeys(2),
      collisionKeys(1),
      collisionKeys(3),
      collisionKeys(0),
      collisionKeys(2),
      collisionKeys(3),
      collisionKeys(1),
      collisionKeys(0),
      collisionKeys(3)
    )
    val (table, reference, sources) = fixture(ids, Array.fill(ids.length)(true))
    val index =
      build(sources, reference, table.schema, SecondaryIndexLayout.FlatHashRows)
    try
      collisionKeys.foreach: key =>
        assertEquals(
          ordinals(lookup(index, key)),
          ids.indices.filter(ids(_) == key).toVector,
          s"key=$key"
        )
      assertEquals(
        ordinals(lookup(index, collisionKeys.reverse.toArray)),
        ids.indices.toVector
      )
      assertEquals(
        index.ownedBytes,
        Int32SecondaryIndex.flatCapacityFor(ids.length).toLong * 8L
      )
      assertEquals(
        Int32SecondaryIndex.flatCapacityFor(1000000).toLong * 8L,
        10666672L
      )
    finally
      index.close()
      table.close()

  test("flat row hash handles an all-equal multi-batch source without rescanning semantics"):
    val ids = Array.fill(257)(Int.MaxValue)
    val valid = Array.tabulate(ids.length)(_ % 17 != 0)
    val (table, reference, sources) =
      fixture(
        Vector(ids.take(64), ids.slice(64, 193), ids.drop(193)),
        Vector(valid.take(64), valid.slice(64, 193), valid.drop(193)),
        "secondary-index-flat-all-equal"
      )
    val index =
      build(sources, reference, table.schema, SecondaryIndexLayout.FlatHashRows)
    try
      assertEquals(
        ordinals(lookup(index, Int.MaxValue)),
        ids.indices.filter(valid).toVector
      )
      assertEquals(lookup(index, Int.MinValue).size, 0)
    finally
      index.close()
      table.close()

  test("grouped hash stores unique ordinals directly and only duplicate groups overflow"):
    assert(Int32SecondaryIndex.MaxRows < (1L << 30))
    val ids = Array(7, 2, 7, 9, 7, 2, 11, 13)
    val valid = Array(true, true, true, false, true, true, true, true)
    val (table, reference, sources) =
      fixture(
        Vector(ids.take(3), ids.slice(3, 6), ids.drop(6)),
        Vector(valid.take(3), valid.slice(3, 6), valid.drop(6)),
        "secondary-index-grouped-overflow"
      )
    val index =
      build(sources, reference, table.schema, SecondaryIndexLayout.GroupedHash)
    try
      assertEquals(ordinals(lookup(index, 7)), Vector(0, 2, 4))
      assertEquals(ordinals(lookup(index, 2)), Vector(1, 5))
      assertEquals(ordinals(lookup(index, 11)), Vector(6))
      assertEquals(
        ordinals(lookup(index, Array(13, 7, 2, 13))),
        Vector(0, 1, 2, 4, 5, 7)
      )
      val distinct = 4
      val duplicateGroups = 2
      val rowsInDuplicateGroups = 5
      assertEquals(
        index.ownedBytes,
        Int32SecondaryIndex.flatCapacityFor(distinct).toLong * 8L +
          duplicateGroups.toLong * 4L +
          rowsInDuplicateGroups.toLong * 4L
      )
    finally
      index.close()
      table.close()

  test("grouped hash retained memory decreases with duplicate fan-out"):
    def owned(ids: Array[Int]): Long =
      val (table, reference, sources) =
        fixture(ids, Array.fill(ids.length)(true))
      val index =
        build(sources, reference, table.schema, SecondaryIndexLayout.GroupedHash)
      try index.ownedBytes
      finally
        index.close()
        table.close()

    val unique = Array.tabulate(1024)(identity)
    val fanoutEight = Array.tabulate(1024)(_ / 8)
    val allEqual = Array.fill(1024)(1)
    assertEquals(
      owned(unique),
      Int32SecondaryIndex.flatCapacityFor(1024).toLong * 8L
    )
    assertEquals(
      owned(fanoutEight),
      Int32SecondaryIndex.flatCapacityFor(128).toLong * 8L +
        128L * 4L +
        1024L * 4L
    )
    assertEquals(
      owned(allEqual),
      Int32SecondaryIndex.flatCapacityFor(1).toLong * 8L + 4L + 1024L * 4L
    )
    assert(owned(allEqual) < owned(fanoutEight))
    assert(owned(fanoutEight) < owned(unique))

  test("large multi-key results take the allocation-neutral merge path"):
    assertEquals(
      SecondaryIndexBatchStrategy.choose(
        matches = 127,
        streams = 32,
        longestStream = 4,
        workspace = 32,
        balancedStrategy = SecondaryIndexBatchStrategy.HeapMerge,
        dominantStrategy = SecondaryIndexBatchStrategy.DominantMerge
      ),
      SecondaryIndexBatchStrategy.Sort
    )
    assertEquals(
      SecondaryIndexBatchStrategy.choose(
        matches = 256,
        streams = 3,
        longestStream = 86,
        workspace = 3,
        balancedStrategy = SecondaryIndexBatchStrategy.HeapMerge,
        dominantStrategy = SecondaryIndexBatchStrategy.DominantMerge
      ),
      SecondaryIndexBatchStrategy.Sort
    )
    assertEquals(
      SecondaryIndexBatchStrategy.choose(
        matches = 128,
        streams = 4,
        longestStream = 32,
        workspace = 4,
        balancedStrategy = SecondaryIndexBatchStrategy.HeapMerge,
        dominantStrategy = SecondaryIndexBatchStrategy.DominantMerge
      ),
      SecondaryIndexBatchStrategy.HeapMerge
    )
    assertEquals(
      SecondaryIndexBatchStrategy.choose(
        matches = 8000,
        streams = 32,
        longestStream = 7900,
        workspace = 32,
        balancedStrategy = SecondaryIndexBatchStrategy.HeapMerge,
        dominantStrategy = SecondaryIndexBatchStrategy.DominantMerge
      ),
      SecondaryIndexBatchStrategy.Sort
    )
    assertEquals(
      SecondaryIndexBatchStrategy.choose(
        matches = 7842,
        streams = 30,
        longestStream = 7813,
        workspace = 32,
        balancedStrategy = SecondaryIndexBatchStrategy.HeapMerge,
        dominantStrategy = SecondaryIndexBatchStrategy.DominantMerge
      ),
      SecondaryIndexBatchStrategy.DominantMerge
    )
    assertEquals(
      SecondaryIndexBatchStrategy.choose(
        matches = 512,
        streams = 1,
        longestStream = 512,
        workspace = 1,
        balancedStrategy = SecondaryIndexBatchStrategy.HeapMerge,
        dominantStrategy = SecondaryIndexBatchStrategy.DominantMerge
      ),
      SecondaryIndexBatchStrategy.DominantMerge
    )
    assertEquals(
      SecondaryIndexBatchStrategy.choose(
        matches = 7842,
        streams = 30,
        longestStream = 7813,
        workspace = 32,
        balancedStrategy = SecondaryIndexBatchStrategy.HeapMerge,
        dominantStrategy = SecondaryIndexBatchStrategy.HeapMerge
      ),
      SecondaryIndexBatchStrategy.HeapMerge
    )
    assertEquals(
      SecondaryIndexBatchStrategy.choose(
        matches = 256,
        streams = 32,
        longestStream = 8,
        workspace = 32,
        balancedStrategy = SecondaryIndexBatchStrategy.Sort,
        dominantStrategy = SecondaryIndexBatchStrategy.DominantMerge
      ),
      SecondaryIndexBatchStrategy.Sort
    )
    val ids = Array.tabulate(512): row =>
      (row * 37) % 64
    val valid = Array.tabulate(ids.length)(_ % 19 != 0)
    val queries = Array.tabulate(32)(identity).reverse ++ Array(7, 7, 12)
    val expectedKeys = queries.toSet
    val expected = ids.indices.filter: row =>
      valid(row) && expectedKeys.contains(ids(row))
    val (table, reference, sources) =
      fixture(
        Vector(ids.take(129), ids.slice(129, 385), ids.drop(385)),
        Vector(valid.take(129), valid.slice(129, 385), valid.drop(385)),
        "secondary-index-batch-merge"
      )
    try
      layouts.foreach: layout =>
        val index = build(sources, reference, table.schema, layout)
        try
          val selection = lookup(index, queries)
          val sortedControl =
            index
              .lookupSortedBatchControl(queries)
              .fold(error => fail(error.message), identity)
          assert(selection.size >= 128, layout.label)
          assertEquals(ordinals(selection), expected.toVector, layout.label)
          assertEquals(ordinals(sortedControl), expected.toVector, layout.label)
        finally index.close()
    finally table.close()

  test("dominant-stream merge preserves source order without a result sort"):
    val ids = Array.tabulate(512): row =>
      if row % 16 == 0 then row else -1
    val valid = Array.fill(ids.length)(true)
    val queries = Array(-1) ++ Array.tabulate(31)(index => (index + 1) * 16)
    val expectedKeys = queries.toSet
    val expected = ids.indices.filter(row => expectedKeys.contains(ids(row)))
    val (table, reference, sources) =
      fixture(ids, valid)
    try
      layouts.foreach: layout =>
        val index = build(sources, reference, table.schema, layout)
        try
          assertEquals(
            ordinals(lookup(index, queries)),
            expected.toVector,
            layout.label
          )
        finally index.close()
    finally table.close()

  test("dominant-stream scratch compacts hits before many missing query keys"):
    val ids = Array.fill(512)(7)
    val valid = Array.fill(ids.length)(true)
    val queries = Array(7) ++ Array.tabulate(1000)(index => 10000 + index)
    val expectedQuery = queries.clone()
    val (table, reference, sources) =
      fixture(ids, valid)
    try
      layouts.foreach: layout =>
        val index = build(sources, reference, table.schema, layout)
        try
          assertEquals(
            ordinals(lookup(index, queries)),
            ids.indices.toVector,
            layout.label
          )
          assertEquals(queries.toVector, expectedQuery.toVector, layout.label)
        finally index.close()
    finally table.close()

  test("batch lookup never mutates caller-owned query keys"):
    val ids = Array(3, 1, 3, 2, 1, 4)
    val query = Array(4, 1, 3, 1, Int.MinValue)
    val expectedQuery = query.clone()
    val (table, reference, sources) = fixture(ids, Array.fill(ids.length)(true))
    try
      layouts.foreach: layout =>
        val index = build(sources, reference, table.schema, layout)
        try
          val _ = lookup(index, query)
          assertEquals(query.toVector, expectedQuery.toVector, layout.label)
        finally index.close()
    finally table.close()

  test("layouts agree with stable scans across randomized nulls, duplicates, and extremes"):
    val random = new scala.util.Random(0x5ec0adL)
    var trial = 0
    while trial < 64 do
      val size = random.nextInt(129)
      val ids = Array.tabulate(size): row =>
        row % 17 match
          case 0 => Int.MinValue
          case 1 => Int.MaxValue
          case _ => random.nextInt(23) - 11
      val valid = Array.fill(size)(random.nextInt(5) != 0)
      val queries =
        Array.fill(1 + random.nextInt(16))(random.nextInt(27) - 13) ++
          Array(Int.MinValue, Int.MaxValue, Int.MinValue)
      val expectedKeys = queries.toSet
      val expected = ids.indices.filter: row =>
        valid(row) && expectedKeys.contains(ids(row))
      val (table, reference, sources) =
        fixture(ids, valid)
      try
        layouts.foreach: layout =>
          val index = build(sources, reference, table.schema, layout)
          try
            assertEquals(
              ordinals(lookup(index, queries)),
              expected.toVector,
              s"trial=$trial layout=${layout.label}"
            )
            assertEquals(
              ordinals(lookup(index, queries.reverse)),
              expected.toVector,
              s"query permutation trial=$trial layout=${layout.label}"
            )
            queries.distinct.foreach: key =>
              val expectedSingle = ids.indices.filter: row =>
                valid(row) && ids(row) == key
              assertEquals(
                ordinals(lookup(index, key)),
                expectedSingle.toVector,
                s"direct key=$key trial=$trial layout=${layout.label}"
              )
          finally index.close()
      finally table.close()
      trial += 1

  test("batch partitioning does not change lookup results"):
    val ids = Array(Int.MaxValue, 3, -1, 3, Int.MinValue, 8, 3, -1)
    val valid = Array(true, true, false, true, true, false, true, true)
    val (oneTable, oneReference, oneSources) =
      fixture(ids, valid)
    val (manyTable, manyReference, manySources) =
      fixture(
        Vector(ids.take(2), ids.slice(2, 5), ids.drop(5)),
        Vector(valid.take(2), valid.slice(2, 5), valid.drop(5)),
        "secondary-index-multi-batch"
      )
    val queries = Array(3, Int.MinValue, Int.MaxValue, -1, 3)
    try
      layouts.foreach: layout =>
        val one = build(oneSources, oneReference, oneTable.schema, layout)
        val many = build(manySources, manyReference, manyTable.schema, layout)
        try
          assertEquals(
            ordinals(lookup(many, queries)),
            ordinals(lookup(one, queries)),
            layout.label
          )
        finally
          one.close()
          many.close()
    finally
      oneTable.close()
      manyTable.close()

  test("all layouts detach their arrays from source lifetime"):
    val ids = Array(5, 1, 5, 2)
    val (table, reference, sources) =
      fixture(ids, Array.fill(ids.length)(true))
    val indices = layouts.map: layout =>
      build(sources, reference, table.schema, layout)
    table.close()
    indices.foreach: index =>
      try
        assertEquals(
          ordinals(lookup(index, 5)),
          Vector(0, 2),
          index.layout.label
        )
      finally index.close()

  test("builder rejects invalid and non-Int32 columns"):
    val ids = Array(0, 1, 2)
    val (table, reference, sources) = fixture(ids, Array.fill(ids.length)(true))
    try
      assertEquals(
        Int32SecondaryIndex.build(sources, reference, table.schema, 2),
        Left(SecondaryIndexError.InvalidColumnIndex(2, 2))
      )
      assertEquals(
        Int32SecondaryIndex.build(
          ReferenceSources.empty,
          reference,
          table.schema,
          0
        ),
        Left(
          SecondaryIndexError.Source(
            ExecutionError.MissingSource(reference.id)
          )
        )
      )
    finally table.close()

    type FloatRow = (id: Double)
    val floatSchema = summon[SchemaDescriptor[FloatRow]].schema
    val floatTable = storage:
      Table.takeOwnership[FloatRow](
        Vector(
          storage:
            RecordBatch(
              floatSchema,
              Vector(storage(ColumnArray.float64(Array(0.0, 1.0, 2.0))))
            )
        )
      )
    val floatReference = frame:
      SourceRef.values("secondary-index-float", "secondary-index-float")
    val floatSources = ReferenceSources.empty.bind(floatReference, floatTable)
    try
      assertEquals(
        Int32SecondaryIndex.build(floatSources, floatReference, floatSchema, 0),
        Left(
          SecondaryIndexError.UnsupportedColumn(
            0,
            DataType.Float64,
            PhysicalEncoding.Plain
          )
        )
      )
    finally floatTable.close()

  private def build(
      sources: ReferenceSources,
      reference: SourceRef,
      schema: Schema,
      layout: SecondaryIndexLayout
  ): Int32SecondaryIndex =
    Int32SecondaryIndex
      .build(sources, reference, schema, 0, layout)
      .fold(error => fail(error.message), identity)

  private def lookup(index: Int32SecondaryIndex, key: Int): Int32RowSelection =
    index.lookup(key).fold(error => fail(error.message), identity)

  private def lookup(
      index: Int32SecondaryIndex,
      keys: Array[Int]
  ): Int32RowSelection =
    index.lookup(keys).fold(error => fail(error.message), identity)

  private def ordinals(selection: Int32RowSelection): Vector[Int] =
    Vector.tabulate(selection.size): position =>
      selection.rowOrdinal(position).fold(error => fail(error.message), identity)
