package frame4s

class SmallAlgebraSuite extends munit.FunSuite:
  private def reference(id: String): SourceRef =
    SourceRef.values(id, id).fold(error => fail(error.message), identity)

  private def table[S <: scala.NamedTuple.AnyNamedTuple](
      rows: Vector[S],
      batchSize: Int = 2
  )(using SchemaDescriptor[S], RowCodec[S]): Table[S] =
    Table.fromRows(rows, batchSize).fold(error => fail(error.message), identity)

  private def collect[S <: scala.NamedTuple.AnyNamedTuple](
      frame: Frame[S],
      sources: ReferenceSources
  ): Table[S] =
    ReferenceInterpreter
      .prepare(frame.plan, sources)
      .collect[S](using frame.descriptor)
      .fold(error => fail(error.message), identity)

  test("rename, drop, and replace lower to exact Project schemas"):
    type Input = (id: Int, label: String, score: Option[Double])
    type Renamed = (id: Int, name: String, score: Option[Double])
    type Dropped = (id: Int, label: String)
    type Replaced = (id: String, label: String, score: Option[Double])
    type RenamedMany = (key: Int, name: String, score: Option[Double])
    type DroppedMany = (label: String)

    val ref = reference("projection-conveniences")
    val input = table[Input](Vector((id = 1, label = "a", score = Some(2.0))))
    val frame = Frame.values[Input](ref).fold(error => fail(error.message), identity)
    val renamed: Frame[Renamed] = frame.rename("label", "name")
    val dropped: Frame[Dropped] = frame.drop("score")
    val replaced: Frame[Replaced] = frame.replace("id")(_.col("label"))
    val renamedMany: Frame[RenamedMany] =
      frame.renameAll("id" -> "key", "label" -> "name")
    val droppedMany: Frame[DroppedMany] = frame.dropAll("id", "score")
    val sources = ReferenceSources.empty.bind(ref, input)
    val renamedOut = collect(renamed, sources)
    val droppedOut = collect(dropped, sources)
    val replacedOut = collect(replaced, sources)
    val renamedManyOut = collect(renamedMany, sources)
    val droppedManyOut = collect(droppedMany, sources)
    try
      assertEquals(renamed.schema.fields.map(_.name), Vector("id", "name", "score"))
      assertEquals(renamedOut.row(0), Right((id = 1, name = "a", score = Some(2.0))))
      assertEquals(droppedOut.row(0), Right((id = 1, label = "a")))
      assertEquals(
        replacedOut.row(0),
        Right((id = "a", label = "a", score = Some(2.0)))
      )
      assertEquals(
        renamedManyOut.row(0),
        Right((key = 1, name = "a", score = Some(2.0)))
      )
      assertEquals(droppedManyOut.row(0), Right(Tuple1("a")))
      Vector(
        renamed,
        dropped,
        replaced,
        renamedMany,
        droppedMany
      ).foreach: projected =>
        assert(projected.explain.startsWith("Project["))
        assertEquals(projected.plan.nodeName, "Project")
    finally
      renamedOut.close()
      droppedOut.close()
      replacedOut.close()
      renamedManyOut.close()
      droppedManyOut.close()
      input.close()

  test("dynamic projection conveniences reject missing fields and collisions"):
    type Input = (id: Int, label: String)
    val ref = reference("dynamic-projection")
    val frame = Frame.values[Input](ref).fold(error => fail(error.message), identity).dynamic
    assertEquals(frame.rename("missing", "x"), Left(FrameError.ColumnNotFound("missing")))
    assertEquals(frame.rename("id", "label"), Left(FrameError.ColumnCollision("label")))
    assertEquals(frame.drop("missing"), Left(FrameError.ColumnNotFound("missing")))
    assertEquals(
      frame.replace("missing", DynamicExpr.literal(LiteralValue.Int32(1))),
      Left(FrameError.ColumnNotFound("missing"))
    )
    assertEquals(
      frame.renameAll("id" -> "key", "id" -> "other"),
      Left(FrameError.DuplicateColumnRequests(Vector("id")))
    )
    assertEquals(
      frame.dropAll("id", "id"),
      Left(FrameError.DuplicateColumnRequests(Vector("id")))
    )
    val id = frame.col("id").fold(error => fail(error.message), identity)
    assertEquals(
      frame.replaceAll("id" -> id, "id" -> id),
      Left(FrameError.DuplicateColumnRequests(Vector("id")))
    )

  test("projection convenience diagnostics reject typed missing names and collisions"):
    val missingDrop = compileErrors("""
      import frame4s.*
      type S = (id: Int, label: String)
      val frame: Frame[S] = ???
      frame.drop("missing")
    """)
    val collision = compileErrors("""
      import frame4s.*
      type S = (id: Int, label: String)
      val frame: Frame[S] = ???
      frame.rename("id", "label")
    """)
    assert(clue(missingDrop).contains("Column 'missing'"))
    assert(clue(collision).contains("already exists"))

    val duplicateRequests = compileErrors("""
      import frame4s.*
      type S = (id: Int, label: String)
      val frame: Frame[S] = ???
      frame.dropAll("id", "id")
    """)
    assert(clue(duplicateRequests).contains("duplicated"))

  test("key-only grouping is public in typed and dynamic paths"):
    type Input = (id: Int, label: String)
    type Key = (id: Int)
    val ref = reference("key-only")
    val input = table[Input](
      Vector(
        (id = 1, label = "a"),
        (id = 1, label = "b"),
        (id = 2, label = "c")
      )
    )
    val frame = Frame.values[Input](ref).fold(error => fail(error.message), identity)
    val grouped: Frame[Key] =
      frame
        .groupBy(row => Tuple1(row.col("id").as("id")))
        .aggregate(_ => EmptyTuple)
    val dynamic = frame.dynamic
      .col("id")
      .flatMap(id => frame.dynamic.groupBy("id" -> id))
      .flatMap(_.aggregate())
      .flatMap(_.typed[Key])
      .fold(error => fail(error.message), identity)
    val sources = ReferenceSources.empty.bind(ref, input)
    val typedOut = collect(grouped, sources)
    val dynamicOut = collect(dynamic, sources)
    try
      assertEquals(typedOut.column("id"), Right(Vector(1, 2)))
      assertEquals(dynamicOut.column("id"), Right(Vector(1, 2)))
    finally
      typedOut.close()
      dynamicOut.close()
      input.close()

  test("distinct uses documented logical key equivalence and an independent set oracle"):
    type Input = (
        value: Double,
        optional: Option[Int],
        text: String,
        at: TimestampMicros
    )
    val nanA = java.lang.Double.longBitsToDouble(0x7ff8000000000001L)
    val nanB = java.lang.Double.longBitsToDouble(0x7ff8000000000011L)
    val rows: Vector[Input] = Vector(
      (value = nanA, optional = None, text = "東京", at = TimestampMicros(1L)),
      (value = nanB, optional = None, text = "東京", at = TimestampMicros(1L)),
      (value = 0.0, optional = Some(1), text = "zero", at = TimestampMicros(2L)),
      (value = -0.0, optional = Some(1), text = "zero", at = TimestampMicros(2L)),
      (value = 1.5, optional = Some(2), text = "x", at = TimestampMicros(3L)),
      (value = 1.5, optional = Some(2), text = "x", at = TimestampMicros(3L))
    )
    val ref = reference("distinct-values")
    val input = table(rows)
    val frame = Frame.values[Input](ref).fold(error => fail(error.message), identity)
    val query = frame.distinct
    val output = collect(query, ReferenceSources.empty.bind(ref, input))
    def canonical(row: Input): String =
      val floating =
        if row.value.isNaN then "NaN"
        else if row.value == 0.0 then "0"
        else java.lang.Double.doubleToLongBits(row.value).toString
      s"$floating|${row.optional}|${row.text}|${row.at.toLong}"
    try
      val actual = Vector
        .tabulate(output.rowCount.toInt)(index =>
          output.row(index).fold(error => fail(error.message), identity)
        )
        .map(canonical)
        .toSet
      assertEquals(actual, rows.map(canonical).toSet)
      assertEquals(output.rowCount, 3L)
      assertEquals(query.plan.nodeName, "Aggregate")
      assertEquals(query.plan.order, OrderGuarantee.Unspecified)
      assert(query.explain.startsWith("Aggregate["))
    finally
      output.close()
      input.close()

  test("distinct compares decoded dictionary values rather than dictionary identity"):
    type Input = (word: String)
    val descriptor = summon[SchemaDescriptor[Input]]
    def dictionaryBatch(
        indices: Array[Int],
        dictionary: Array[String]
    ): RecordBatch =
      val indexArray = ColumnArray.int32(indices).fold(error => fail(error.message), identity)
      val values = ColumnArray.utf8(dictionary).fold(error => fail(error.message), identity)
      val encoded = ColumnArray
        .dictionary(indexArray, values)
        .fold(error => fail(error.message), identity)
      RecordBatch(descriptor.schema, Vector(encoded))
        .fold(error => fail(error.message), identity)

    val input = Table[Input](
      Vector(
        dictionaryBatch(Array(0, 1), Array("a", "b")),
        dictionaryBatch(Array(1, 0), Array("b", "a"))
      )
    ).fold(error => fail(error.message), identity)
    val ref = reference("dictionary-distinct")
    val frame = Frame.values[Input](ref).fold(error => fail(error.message), identity)
    val output = collect(frame.distinct, ReferenceSources.empty.bind(ref, input))
    try assertEquals(output.column("word").map(_.toSet), Right(Set("a", "b")))
    finally
      output.close()
      input.close()

  test("dropping the last field preserves zero-column cardinality and distinctness"):
    type Input = (id: Int)
    type Empty = scala.NamedTuple.NamedTuple[EmptyTuple, EmptyTuple]
    val ref = reference("zero-column")
    val input = table[Input](Vector((id = 1), (id = 2), (id = 3)))
    val frame = Frame.values[Input](ref).fold(error => fail(error.message), identity)
    val query: Frame[Empty] = frame.drop("id").distinct
    val output = collect(query, ReferenceSources.empty.bind(ref, input))
    try
      assertEquals(output.schema.size, 0)
      assertEquals(output.rowCount, 1L)
      assertEquals(output.row(0), Right(EmptyTuple))
    finally
      output.close()
      input.close()

  test("right join is a swapped LeftOuter plus Project with original output order"):
    type Left = (id: Option[Int], leftValue: String)
    type Right = (key: Option[Int], rightValue: String)
    type Output = (
        id: Option[Int],
        leftValue: Option[String],
        key: Option[Int],
        rightValue: String
    )
    val leftRef = reference("right-join-left")
    val rightRef = reference("right-join-right")
    val leftTable = table[Left](
      Vector((id = Some(1), leftValue = "one"), (id = Some(2), leftValue = "two"))
    )
    val rightTable = table[Right](
      Vector(
        (key = Some(2), rightValue = "matched-a"),
        (key = Some(2), rightValue = "matched-b"),
        (key = None, rightValue = "null-predicate")
      )
    )
    val left = Frame.values[Left](leftRef).fold(error => fail(error.message), identity)
    val right = Frame.values[Right](rightRef).fold(error => fail(error.message), identity)
    val query: Frame[Output] =
      left.rightJoin(right)((lhs, rhs) => (lhs.col("id") === rhs.col("key")).isTrue)
    val output = collect(
      query,
      ReferenceSources.empty.bind(leftRef, leftTable).bind(rightRef, rightTable)
    )
    try
      assertEquals(
        Vector.tabulate(output.rowCount.toInt)(index =>
          output.row(index).fold(error => fail(error.message), identity)
        ),
        Vector(
          (
            id = Some(2),
            leftValue = Some("two"),
            key = Some(2),
            rightValue = "matched-a"
          ),
          (
            id = Some(2),
            leftValue = Some("two"),
            key = Some(2),
            rightValue = "matched-b"
          ),
          (
            id = None,
            leftValue = None,
            key = None,
            rightValue = "null-predicate"
          )
        )
      )
      assert(query.explain.startsWith("Project["))
      assert(query.explain.contains("Join[LeftOuter"))
      assert(!query.explain.contains("RightOuter"))
    finally
      output.close()
      leftTable.close()
      rightTable.close()

  test("dynamic right, semi, and anti joins preserve the R4 schemas and kinds"):
    type Left = (id: Int)
    type Right = (key: Int)
    type RightOutput = (id: Option[Int], key: Int)
    val leftRef = reference("dynamic-r4-left")
    val rightRef = reference("dynamic-r4-right")
    val left = Frame.values[Left](leftRef).fold(error => fail(error.message), identity)
    val right = Frame.values[Right](rightRef).fold(error => fail(error.message), identity)
    def equal(leftScope: DynamicScope, rightScope: DynamicScope) =
      for
        id <- leftScope.col("id")
        key <- rightScope.col("key")
        result <- id === key
      yield result
    val dynamicRight = left.dynamic
      .rightJoin(right.dynamic)(equal)
      .flatMap(_.typed[RightOutput])
      .fold(error => fail(error.message), identity)
    val dynamicSemi = left.dynamic
      .semiJoin(right.dynamic)(equal)
      .flatMap(_.typed[Left])
      .fold(error => fail(error.message), identity)
    val dynamicAnti = left.dynamic
      .antiJoin(right.dynamic)(equal)
      .flatMap(_.typed[Left])
      .fold(error => fail(error.message), identity)
    assert(dynamicRight.explain.startsWith("Project["))
    assert(dynamicRight.explain.contains("Join[LeftOuter"))
    assert(dynamicSemi.explain.contains("Join[LeftSemi"))
    assert(dynamicAnti.explain.contains("Join[LeftAnti"))

  test("unionAll preserves duplicates, stable concatenation, and explains both branches"):
    type Input = (id: Int)
    val leftRef = reference("union-left")
    val rightRef = reference("union-right")
    val leftTable = table[Input](Vector((id = 1), (id = 1)), batchSize = 1)
    val rightTable = table[Input](Vector((id = 2), (id = 1)), batchSize = 1)
    val left = Frame.values[Input](leftRef).fold(error => fail(error.message), identity)
    val right = Frame.values[Input](rightRef).fold(error => fail(error.message), identity)
    val query = left.unionAll(right)
    val sources = ReferenceSources.empty.bind(leftRef, leftTable).bind(rightRef, rightTable)
    val output = collect(query, sources)
    try
      assertEquals(output.column("id"), Right(Vector(1, 1, 2, 1)))
      assertEquals(query.plan.order, OrderGuarantee.Stable)
      assert(query.explain.startsWith("UnionAll[order=Stable]"))
      assertEquals(query.plan.children.length, 2)
      val physical = ReferenceInterpreter.prepare(query.plan, sources).physicalExplain
      assert(physical.contains("operators=UnionAll>Values>Values"))
    finally
      output.close()
      leftTable.close()
      rightTable.close()

  test("unionAll evaluates left failures before opening the right branch"):
    type Input = (id: Int)
    val leftRef = reference("union-failure-left")
    val rightRef = reference("union-failure-right")
    val leftTable = table[Input](Vector((id = Int.MaxValue)))
    val rightTable = table[Input](Vector((id = 1)))
    val left = Frame
      .values[Input](leftRef)
      .fold(error => fail(error.message), identity)
      .withColumn("next")(_.col("id") + Expr.literal(1))
    val right = Frame
      .values[Input](rightRef)
      .fold(error => fail(error.message), identity)
      .withColumn("next")(_.col("id") + Expr.literal(1))
    rightTable.close()
    val query = left.unionAll(right)
    val result = ReferenceInterpreter
      .prepare(
        query.plan,
        ReferenceSources.empty.bind(leftRef, leftTable).bind(rightRef, rightTable)
      )
      .collect(using query.descriptor)
    result match
      case Left(ExecutionError.IntegerOverflow(_, BinaryOperator.Add)) => ()
      case other => fail(s"expected left overflow before closed right source, found $other")
    leftTable.close()

  test("dynamic union mismatch reports ordered field issues"):
    type Left = (id: Int, value: Option[Double])
    type Right = (key: Long, value: Double)
    val left = Frame
      .values[Left](reference("dynamic-union-left"))
      .fold(error => fail(error.message), identity)
      .dynamic
    val right = Frame
      .values[Right](reference("dynamic-union-right"))
      .fold(error => fail(error.message), identity)
      .dynamic
    assertEquals(
      left.unionAll(right),
      Left(
        FrameError.SchemaMismatch(
          Vector(
            BindingIssue.FieldName(0, "id", "key"),
            BindingIssue.FieldType(0, DataType.Int32, DataType.Int64),
            BindingIssue.FieldNullability(1, true, false)
          )
        )
      )
    )

  test("semi and anti joins implement existential truth without right multiplicity"):
    type Left = (id: Int, key: Option[Int])
    type Right = (rightKey: Option[Int], tag: String)
    val leftRef = reference("semi-left")
    val rightRef = reference("semi-right")
    val leftRows: Vector[Left] = Vector(
      (id = 1, key = Some(1)),
      (id = 2, key = Some(2)),
      (id = 3, key = None),
      (id = 4, key = Some(2))
    )
    val rightRows: Vector[Right] = Vector(
      (rightKey = Some(2), tag = "a"),
      (rightKey = Some(2), tag = "b"),
      (rightKey = None, tag = "null")
    )
    val leftTable = table(leftRows)
    val rightTable = table(rightRows)
    val left = Frame.values[Left](leftRef).fold(error => fail(error.message), identity)
    val right = Frame.values[Right](rightRef).fold(error => fail(error.message), identity)
    val semi = left.semiJoin(right): (lhs, rhs) =>
      (lhs.col("key") === rhs.col("rightKey")).isTrue
    val anti = left.antiJoin(right): (lhs, rhs) =>
      (lhs.col("key") === rhs.col("rightKey")).isTrue
    val sources = ReferenceSources.empty.bind(leftRef, leftTable).bind(rightRef, rightTable)
    val semiOut = collect(semi, sources)
    val antiOut = collect(anti, sources)
    try
      assertEquals(semiOut.column("id"), Right(Vector(2, 4)))
      assertEquals(antiOut.column("id"), Right(Vector(1, 3)))
      assertEquals(semi.plan.order, OrderGuarantee.Stable)
      assertEquals(anti.plan.order, OrderGuarantee.Stable)
      assert(semi.explain.contains("Join[LeftSemi"))
      assert(anti.explain.contains("Join[LeftAnti"))
      val physical = ReferenceInterpreter.prepare(semi.plan, sources).physicalExplain
      assert(physical.contains("Join:LeftSemi"))
    finally
      semiOut.close()
      antiOut.close()
      leftTable.close()
      rightTable.close()

  test("semi and anti joins handle empty right and non-equi predicates"):
    type Left = (id: Int)
    type Right = (threshold: Int)
    val leftRef = reference("existence-empty-left")
    val rightRef = reference("existence-empty-right")
    val leftTable = table[Left](Vector((id = 1), (id = 3)))
    val rightTable = table[Right](Vector.empty)
    val left = Frame.values[Left](leftRef).fold(error => fail(error.message), identity)
    val right = Frame.values[Right](rightRef).fold(error => fail(error.message), identity)
    val semi = left.semiJoin(right)((lhs, rhs) => lhs.col("id") < rhs.col("threshold"))
    val anti = left.antiJoin(right)((lhs, rhs) => lhs.col("id") < rhs.col("threshold"))
    val sources = ReferenceSources.empty.bind(leftRef, leftTable).bind(rightRef, rightTable)
    val semiOut = collect(semi, sources)
    val antiOut = collect(anti, sources)
    try
      assertEquals(semiOut.rowCount, 0L)
      assertEquals(antiOut.column("id"), Right(Vector(1, 3)))
    finally
      semiOut.close()
      antiOut.close()
      leftTable.close()
      rightTable.close()

  test("semi join short-circuits right evaluation after the first true witness"):
    type Left = (id: Int)
    type Right = (key: Int)
    val leftRef = reference("semi-short-left")
    val rightRef = reference("semi-short-right")
    val leftTable = table[Left](Vector((id = 0)))
    val rightTable = table[Right](Vector((key = 0), (key = 1)))
    val left = Frame.values[Left](leftRef).fold(error => fail(error.message), identity)
    val right = Frame.values[Right](rightRef).fold(error => fail(error.message), identity)
    val query = left.semiJoin(right): (lhs, rhs) =>
      (lhs.col("id") === rhs.col("key")) ||
        ((Expr.literal(Int.MaxValue) + rhs.col("key")) > Expr.literal(0))
    val output = collect(
      query,
      ReferenceSources.empty.bind(leftRef, leftTable).bind(rightRef, rightTable)
    )
    try assertEquals(output.column("id"), Right(Vector(0)))
    finally
      output.close()
      leftTable.close()
      rightTable.close()

  test("sqrt is floating-only, null-preserving, IEEE, total, and visible in explain"):
    type Input = (f: Float, d: Double, optional: Option[Double])
    type Output = (f: Float, d: Double, optional: Option[Double])
    val ref = reference("sqrt")
    val input = table[Input](
      Vector(
        (f = -0.0f, d = -1.0, optional = None),
        (f = Float.PositiveInfinity, d = Double.NaN, optional = Some(9.0))
      )
    )
    val frame = Frame.values[Input](ref).fold(error => fail(error.message), identity)
    val query: Frame[Output] = frame.select: row =>
      (
        row.col("f").sqrt.as("f"),
        row.col("d").sqrt.as("d"),
        row.col("optional").sqrt.as("optional")
      )
    val output = collect(query, ReferenceSources.empty.bind(ref, input))
    try
      val first = output.row(0).fold(error => fail(error.message), identity)
      val second = output.row(1).fold(error => fail(error.message), identity)
      assertEquals(
        java.lang.Float.floatToRawIntBits(first.f),
        java.lang.Float.floatToRawIntBits(-0.0f)
      )
      assert(first.d.isNaN)
      assertEquals(first.optional, None)
      assertEquals(second.f, Float.PositiveInfinity)
      assert(second.d.isNaN)
      assertEquals(second.optional, Some(3.0))
      assert(query.explain.contains("Sqrt("))
      val (_, receipt) = query.normalized
      assert(receipt.normalizedExplain.contains("Sqrt("))
    finally
      output.close()
      input.close()

  test("sqrt dynamic and compile-time diagnostics reject non-floating expressions"):
    val dynamic = DynamicExpr.literal(LiteralValue.Int32(4)).sqrt
    assertEquals(dynamic, Left(FrameError.ExpressionType(DataType.Float64, DataType.Int32)))
    val typed = compileErrors("""
      import frame4s.*
      type S = (id: Int)
      val frame: Frame[S] = ???
      frame.select(row => Tuple1(row.col("id").sqrt.as("root")))
    """)
    assert(typed.contains("sqrt requires"))

  test("variancePop and stddevPop use population denominator and null policy"):
    type Input = (group: String, value: Option[Double])
    type Output = (
        group: String,
        variancePop: Option[Double],
        stddevPop: Option[Double]
    )
    val ref = reference("population-statistics")
    val input = table[Input](
      Vector(
        (group = "a", value = Some(1.0)),
        (group = "a", value = Some(2.0)),
        (group = "a", value = Some(3.0)),
        (group = "b", value = None)
      ),
      batchSize = 1
    )
    val frame = Frame.values[Input](ref).fold(error => fail(error.message), identity)
    val query: Frame[Output] =
      frame
        .groupBy(row => Tuple1(row.col("group").as("group")))
        .aggregate: row =>
          (
            Aggregate.variancePop(row.col("value")).as("variancePop"),
            Aggregate.stddevPop(row.col("value")).as("stddevPop")
          )
    val output = collect(query, ReferenceSources.empty.bind(ref, input))
    try
      val rows = Vector.tabulate(output.rowCount.toInt)(index =>
        output.row(index).fold(error => fail(error.message), identity)
      )
      assertEqualsDouble(
        rows.head.variancePop.getOrElse(fail("missing variance")),
        2.0 / 3.0,
        1e-12
      )
      assertEqualsDouble(
        rows.head.stddevPop.getOrElse(fail("missing stddev")),
        math.sqrt(2.0 / 3.0),
        1e-12
      )
      assertEquals(rows(1), (group = "b", variancePop = None, stddevPop = None))
    finally
      output.close()
      input.close()

  test("ambiguous variance name is absent before compatibility baseline"):
    val typed = compileErrors("""
      import frame4s.*
      type S = (value: Double)
      val frame: Frame[S] = ???
      frame.groupBy(row => Tuple1(row.col("value").as("value")))
        .aggregate(row => Tuple1(Aggregate.variance(row.col("value")).as("v")))
    """)
    assert(typed.contains("value variance is not a member"))
