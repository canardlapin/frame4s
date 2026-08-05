package frame4s

class TableOwnershipSuite extends munit.FunSuite:
  type Row = (id: Int)
  type Other = (value: Long)
  type Pair = (id: Int, label: String)
  type OptionalRow = (id: Option[Int])
  type OptionalText = (label: Option[String])

  private def value[E, A](result: Either[E, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def rowBatch(values: Array[Int], tracker: BufferTracker): RecordBatch =
    value(
      RecordBatch(
        summon[SchemaDescriptor[Row]].schema,
        Vector(value(ColumnArray.int32(values, tracker = tracker)))
      )
    )

  private def otherBatch(values: Array[Long], tracker: BufferTracker): RecordBatch =
    value(
      RecordBatch(
        summon[SchemaDescriptor[Other]].schema,
        Vector(value(ColumnArray.int64(values, tracker = tracker)))
      )
    )

  private def optionalRowBatch(
      values: Array[Int],
      valid: Array[Boolean],
      tracker: BufferTracker
  ): RecordBatch =
    value(
      RecordBatch(
        summon[SchemaDescriptor[OptionalRow]].schema,
        Vector(value(ColumnArray.int32(values, valid, tracker)))
      )
    )

  private def dictionaryBatch(tracker: BufferTracker): RecordBatch =
    val indices = value(ColumnArray.int32(Array(1, 0), Array(true, false), tracker))
    val values = value(ColumnArray.utf8(Array("first", "second"), tracker = tracker))
    val dictionary = value(ColumnArray.dictionary(indices, values))
    value(
      RecordBatch(
        summon[SchemaDescriptor[OptionalText]].schema,
        Vector(dictionary)
      )
    )

  private def assertReleased(tracker: BufferTracker): Unit =
    assertEquals(tracker.snapshot.activeOwners, 0)
    assertEquals(tracker.snapshot.activeViews, 0)

  private def assertInputFailure[S <: scala.NamedTuple.AnyNamedTuple](
      result: Either[TableReadError, Table[S]],
      stage: TableInputStage,
      row: Long
  ): Unit =
    result match
      case Left(TableReadError.InputFailure(actualStage, actualRow, detail)) =>
        assertEquals(actualStage, stage)
        assertEquals(actualRow, row)
        assert(detail.nonEmpty)
      case other => fail(s"expected $stage input failure at row $row, found $other")

  test("the public API requires an explicit transfer or retain choice"):
    val ambiguous = compileErrors("""
      import frame4s.*
      type Row = (id: Int)
      def invalid(batches: Vector[RecordBatch]) = Table[Row](batches)
    """)
    val explicit = compileErrors("""
      import frame4s.*
      type Row = (id: Int)
      def valid(batches: Vector[RecordBatch]) =
        (Table.takeOwnership[Row](batches), Table.retainFrom[Row](batches))
    """)

    assert(ambiguous.nonEmpty)
    assertEquals(explicit, "")

  test("takeOwnership consumes batches and closes every input on validation failure"):
    val successTracker = new BufferTracker
    val owned = rowBatch(Array(1, 2), successTracker)
    val table = value(Table.takeOwnership[Row](Vector(owned)))

    assertEquals(table.row(1), Right((id = 2)))
    table.close()
    table.close()
    assert(owned.isClosed)
    assertReleased(successTracker)

    val goodTracker = new BufferTracker
    val wrongTracker = new BufferTracker
    val good = rowBatch(Array(1), goodTracker)
    val wrong = otherBatch(Array(2L), wrongTracker)
    assertEquals(
      Table.takeOwnership[Row](Vector(good, wrong)),
      Left(
        StorageError.SchemaMismatch(
          summon[SchemaDescriptor[Row]].schema,
          summon[SchemaDescriptor[Other]].schema
        )
      )
    )
    assert(good.isClosed)
    assert(wrong.isClosed)
    assertReleased(goodTracker)
    assertReleased(wrongTracker)

  test("retainFrom gives the table an independent lifetime and preserves inputs on failure"):
    val tracker = new BufferTracker
    val original = rowBatch(Array(7, 8), tracker)
    val retained = value(Table.retainFrom[Row](Vector(original)))

    original.close()
    assertEquals(retained.row(0), Right((id = 7)))
    assertEquals(retained.row(1), Right((id = 8)))
    retained.close()
    assertReleased(tracker)

    val goodTracker = new BufferTracker
    val wrongTracker = new BufferTracker
    val good = rowBatch(Array(1), goodTracker)
    val wrong = otherBatch(Array(2L), wrongTracker)
    assertEquals(
      Table.retainFrom[Row](Vector(good, wrong)),
      Left(
        StorageError.SchemaMismatch(
          summon[SchemaDescriptor[Row]].schema,
          summon[SchemaDescriptor[Other]].schema
        )
      )
    )
    assert(!good.isClosed)
    assert(!wrong.isClosed)
    assertEquals(value(good.column("id")).scalar(0), Right(ScalarValue.Int32(1)))
    good.close()
    wrong.close()
    assertReleased(goodTracker)
    assertReleased(wrongTracker)

  test("retainBatches gives adapters independent caller-owned views"):
    val firstTracker = new BufferTracker
    val secondTracker = new BufferTracker
    val firstRoot = optionalRowBatch(Array(10, 20, 30), Array(true, false, true), firstTracker)
    val first = value(firstRoot.slice(1, 2))
    firstRoot.close()
    val second = optionalRowBatch(Array(40), Array(true), secondTracker)
    val expectedLayouts = Vector(first, second).map(batch => value(batch.column("id")).layout)
    val table = value(Table.takeOwnership[OptionalRow](Vector(first, second)))

    val retained = value(table.retainBatches)
    assertEquals(retained.map(_.schema), Vector.fill(2)(table.schema))
    assertEquals(retained.map(_.rowCount), Vector(2, 1))
    assertEquals(retained.map(batch => value(batch.column("id")).layout), expectedLayouts)
    assertEquals(value(retained(0).column("id")).scalar(0), Right(ScalarValue.Null))
    assertEquals(value(retained(0).column("id")).scalar(1), Right(ScalarValue.Int32(30)))
    assertEquals(value(retained(1).column("id")).scalar(0), Right(ScalarValue.Int32(40)))

    retained.foreach(_.close())
    assertEquals(table.row(2), Right((id = Some(40))))

    val afterTable = value(table.retainBatches)
    table.close()
    assertEquals(value(afterTable(0).column("id")).scalar(0), Right(ScalarValue.Null))
    assertEquals(value(afterTable(1).column("id")).scalar(0), Right(ScalarValue.Int32(40)))
    afterTable.foreach(_.close())
    assertReleased(firstTracker)
    assertReleased(secondTracker)

  test("retainBatches preserves dictionary encoding and empty-table behavior"):
    val dictionaryTracker = new BufferTracker
    val dictionary = dictionaryBatch(dictionaryTracker)
    val table = value(Table.takeOwnership[OptionalText](Vector(dictionary)))
    val retained = value(table.retainBatches)

    assertEquals(
      value(retained.head.column("label")).encoding,
      PhysicalEncoding.Dictionary(DataType.Int32, DataType.Utf8)
    )
    assertEquals(
      value(retained.head.column("label")).scalar(0),
      Right(ScalarValue.checkedUtf8("second"))
    )
    assertEquals(value(retained.head.column("label")).scalar(1), Right(ScalarValue.Null))
    retained.foreach(_.close())
    table.close()
    assertReleased(dictionaryTracker)

    val empty = value(Table.takeOwnership[Row](Vector.empty))
    assertEquals(empty.retainBatches, Right(Vector.empty))
    empty.close()
    assertEquals(empty.retainBatches, Left(TableReadError.Closed))

  test("retainBatches unwinds partial retention without closing table-owned batches"):
    val liveTracker = new BufferTracker
    val closedTracker = new BufferTracker
    val live = rowBatch(Array(1), liveTracker)
    val closed = rowBatch(Array(2), closedTracker)
    closed.close()
    val malformed = Table.unsafeAdoptValidated[Row](
      summon[SchemaDescriptor[Row]].schema,
      Vector(live, closed)
    )

    assertEquals(
      malformed.retainBatches,
      Left(TableReadError.Storage(StorageError.BufferClosed))
    )
    assert(!live.isClosed)
    assertEquals(value(live.column("id")).scalar(0), Right(ScalarValue.Int32(1)))
    assertEquals(liveTracker.snapshot.activeViews, 1)
    malformed.close()
    assertReleased(liveTracker)
    assertReleased(closedTracker)

  test("closed inputs are rejected without leaking prior retained views"):
    val liveTracker = new BufferTracker
    val closedTracker = new BufferTracker
    val live = rowBatch(Array(1), liveTracker)
    val closed = rowBatch(Array(2), closedTracker)
    closed.close()

    assertEquals(
      Table.retainFrom[Row](Vector(live, closed)),
      Left(StorageError.BufferClosed)
    )
    assert(!live.isClosed)
    assertEquals(liveTracker.snapshot.activeViews, 1)
    live.close()
    assertReleased(liveTracker)
    assertReleased(closedTracker)

    val transferredTracker = new BufferTracker
    val transferred = rowBatch(Array(3), transferredTracker)
    assertEquals(
      Table.takeOwnership[Row](Vector(transferred, closed)),
      Left(StorageError.BufferClosed)
    )
    assert(transferred.isClosed)
    assertReleased(transferredTracker)

  test("a throwing release callback cannot prevent later owned batches from closing"):
    val borrowedTracker = new BufferTracker
    val ordinaryTracker = new BufferTracker
    var releases = 0
    val borrowed = Buffer.borrowed(
      Array[Byte](1, 0, 0, 0),
      () =>
        releases += 1
        throw new IllegalStateException("release")
      ,
      borrowedTracker
    )
    val borrowedColumn = new Int32Array(
      1,
      0,
      borrowed,
      Validity.fromFlags(Array(true), borrowedTracker)
    )
    val first = value(
      RecordBatch(summon[SchemaDescriptor[Row]].schema, Vector(borrowedColumn))
    )
    val second = rowBatch(Array(2), ordinaryTracker)
    val table = value(Table.takeOwnership[Row](Vector(first, second)))

    val releaseFailure = intercept[IllegalStateException](table.close())
    assertEquals(releaseFailure.getMessage, "release")
    assertEquals(releases, 1)
    assert(first.isClosed)
    assert(second.isClosed)
    assertReleased(borrowedTracker)
    assertReleased(ordinaryTracker)
    table.close()

  test("iterator acquisition, hasNext, and next failures are structured and unwind prior batches"):
    val acquireTracker = new BufferTracker
    val acquisitionFailure = new IterableOnce[Row]:
      def iterator: Iterator[Row] = throw new IllegalStateException("iterator")
    assertInputFailure(
      Table.fromRowsTracked[Row](acquisitionFailure, 2, acquireTracker),
      TableInputStage.AcquireIterator,
      0L
    )
    assertReleased(acquireTracker)

    val hasNextTracker = new BufferTracker
    val hasNextFailure = new IterableOnce[Row]:
      def iterator: Iterator[Row] = new Iterator[Row]:
        private var index = 0
        def hasNext: Boolean =
          if index < 2 then true else throw new IllegalStateException("hasNext")
        def next(): Row =
          index += 1
          (id = index)
    assertInputFailure(
      Table.fromRowsTracked[Row](hasNextFailure, 2, hasNextTracker),
      TableInputStage.HasNext,
      2L
    )
    assertReleased(hasNextTracker)
    assert(hasNextTracker.snapshot.releasedOwners > 0L)

    val nextTracker = new BufferTracker
    val nextFailure = new IterableOnce[Row]:
      def iterator: Iterator[Row] = new Iterator[Row]:
        private var index = 0
        def hasNext: Boolean = true
        def next(): Row =
          if index == 2 then throw new IllegalStateException("next")
          index += 1
          (id = index)
    assertInputFailure(
      Table.fromRowsTracked[Row](nextFailure, 2, nextTracker),
      TableInputStage.Next,
      2L
    )
    assertReleased(nextTracker)
    assert(nextTracker.snapshot.releasedOwners > 0L)

  test("row-encoding and batch-building failures are structured and unwind prior batches"):
    val rows = Vector((id = 1), (id = 2), (id = 3))
    val encodeTracker = new BufferTracker
    val encodeFailure =
      Table.fromRowsTrackedWithEncoder[Row, Row](rows, 2, encodeTracker): row =>
        if row.id == 3 then throw new IllegalStateException("encode")
        Right(Vector(ScalarValue.Int32(row.id)))
    assertInputFailure(
      encodeFailure,
      TableInputStage.EncodeRow,
      2L
    )
    assertReleased(encodeTracker)
    assert(encodeTracker.snapshot.releasedOwners > 0L)

    val pairRows = Vector(
      (id = 1, label = "a"),
      (id = 2, label = "b"),
      (id = 3, label = "c")
    )
    val buildTracker = new BufferTracker
    val buildFailure =
      Table.fromRowsTrackedWithEncoder[Pair, Pair](pairRows, 2, buildTracker): row =>
        if row.id == 3 then Right(Vector(ScalarValue.Int32(row.id)))
        else Right(Vector(ScalarValue.Int32(row.id), ScalarValue.checkedUtf8(row.label)))
    assertInputFailure(
      buildFailure,
      TableInputStage.BuildBatch,
      2L
    )
    assertReleased(buildTracker)
    assert(buildTracker.snapshot.releasedOwners > 0L)

  test("public slice ranges reject overflow and preserve zero-length boundaries"):
    val tracker = new BufferTracker
    val buffer = Buffer.owned(Array[Byte](1, 2, 3), tracker)
    assertEquals(
      buffer.slice(1, Int.MaxValue),
      Left(StorageError.InvalidRange(1, Int.MaxValue, 3))
    )
    assertEquals(
      buffer.slice(Int.MaxValue, 1),
      Left(StorageError.InvalidRange(Int.MaxValue, 1, 3))
    )
    assertEquals(buffer.slice(-1, 1), Left(StorageError.InvalidRange(-1, 1, 3)))
    assertEquals(buffer.slice(0, -1), Left(StorageError.InvalidRange(0, -1, 3)))
    value(buffer.slice(3, 0)).close()
    buffer.close()
    assertReleased(tracker)

    val arrayTracker = new BufferTracker
    val array = value(ColumnArray.int32(Array(1, 2, 3), tracker = arrayTracker))
    assertEquals(
      array.slice(1, Int.MaxValue),
      Left(StorageError.InvalidRange(1, Int.MaxValue, 3))
    )
    val batch = value(
      RecordBatch(summon[SchemaDescriptor[Row]].schema, Vector(array))
    )
    assertEquals(
      batch.slice(1, Int.MaxValue),
      Left(StorageError.InvalidRange(1, Int.MaxValue, 3))
    )
    value(batch.slice(3, 0)).close()
    batch.close()
    assertReleased(arrayTracker)
