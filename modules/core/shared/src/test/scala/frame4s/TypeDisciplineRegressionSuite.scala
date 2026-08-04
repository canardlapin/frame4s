package frame4s

import scala.compiletime.testing.typeCheckErrors

/** Regression markers for type-discipline defects found in the 2026-07-23 review.
  */
class TypeDisciplineRegressionSuite extends munit.FunSuite:
  type People = (id: Int, name: String)

  private def get[T](result: Either[?, T]): T =
    result.fold(error => fail(error.toString), identity)

  private def assertFirstDiagnostic(
      errors: List[scala.compiletime.testing.Error],
      expected: String
  ): Unit =
    val message = errors.headOption.fold(fail("expected a compile error"))(_.message)
    val expectedIndex = message.indexOf(expected)
    val matchTypeIndex = message.indexOf("match type")
    assert(expectedIndex >= 0, message)
    assert(matchTypeIndex < 0 || expectedIndex < matchTypeIndex, message)

  // ---------------------------------------------------------------- Schema.unsafe reachability

  test("typed select rejects duplicate output names at compile time"):
    val errors = typeCheckErrors("""
      import frame4s.*
      type People = (id: Int, name: String)
      val frame = Frame.source[People]("people").toOption.get
      frame.select(row => (row.col("id").as("x"), row.col("name").as("x")))
    """)
    assertFirstDiagnostic(errors, "Output column 'x' is duplicated")

  test("typed select rejects duplicate retained column names at compile time"):
    val errors = typeCheckErrors("""
      import frame4s.*
      type People = (id: Int, name: String)
      val frame = Frame.source[People]("people").toOption.get
      frame.select(row => (row.col("id"), row.col("id")))
    """)
    assertFirstDiagnostic(errors, "Output column 'id' is duplicated")

  test("typed aggregate rejects a key/aggregate name collision at compile time"):
    val errors = typeCheckErrors("""
      import frame4s.*
      type People = (id: Int, name: String)
      val frame = Frame.source[People]("people").toOption.get
      frame
        .groupBy(row => Tuple1(row.col("name").as("k")))
        .aggregate(_ => Tuple1(Aggregate.count.as("k")))
    """)
    assertFirstDiagnostic(errors, "Output column 'k' is duplicated")

  test("using joins do not reserve or mangle user-visible names"):
    type Left = (id: Int, __frame_using_right_0_id: Int)
    type Right = (id: Int)
    val left = get(Frame.source[Left]("left"))
    val right = get(Frame.source[Right]("right"))
    assertEquals(
      left.innerJoinUsing(right, "id").schema.fields.map(_.name),
      Vector("id", "__frame_using_right_0_id")
    )

  // ------------------------------------------------------------------------- storage soundness

  test("a non-nullable column cannot hold a null via dictionary encoding"):
    val dictionary = get(ColumnArray.utf8(Array("red", "blue"), Array(true, false)))
    val indices = get(ColumnArray.int32(Array(0, 1)))
    // A dictionary carrying its own nulls is rejected at construction: DictionaryArray.nullCount
    // reports only index nulls, so such a column could otherwise pass a non-nullable field's
    // validation and still decode to Null.
    assertEquals(
      ColumnArray.dictionary(indices, dictionary).left.map(_.message),
      Left(StorageError.DictionaryContainsNull(1).message)
    )
    indices.close()
    dictionary.close()

  // ------------------------------------------------------------------- typed-plan soundness

  test("an expression cannot be smuggled from one frame into another"):
    val errors = typeCheckErrors("""
      import frame4s.*
      type A = (p: Int, q: Int)
      type B = (q: Int, p: Int)
      val frameA = Frame.source[A]("a").toOption.get
      val frameB = Frame.source[B]("b").toOption.get
      var stolen: ExprOf[Int, frameA.Origin] = null
      frameA.select: scope =>
        stolen = scope.col("p")
        Tuple1(scope.col("p").as("p"))
      frameB.select(_ => Tuple1(stolen.as("p")))
    """)
    assertFirstDiagnostic(errors, "different frame scope")

  test("a retained column expression cannot be smuggled into another frame"):
    val errors = typeCheckErrors("""
      import frame4s.*
      type A = (p: Int, q: Int)
      type B = (q: Int, p: Int)
      val frameA = Frame.source[A]("a").toOption.get
      val frameB = Frame.source[B]("b").toOption.get
      var stolen: ColumnExprOf["p", Int, frameA.Origin] = null
      frameA.select: scope =>
        stolen = scope.col("p")
        Tuple1(scope.col("p"))
      frameB.select(_ => Tuple1(stolen))
    """)
    assertFirstDiagnostic(errors, "different frame scope")

  // ------------------------------------------------------------------ reduction semantics

  test("mean uses IEEE arithmetic and does not manufacture NaN from non-NaN input"):
    type Values = (value: Double)
    val reference = get(SourceRef.values("v", "v"))
    val schema = summon[SchemaDescriptor[Values]].schema
    val batch = get(
      RecordBatch(schema, Vector(get(ColumnArray.float64(Array(Double.PositiveInfinity, 1.0)))))
    )
    val table = get(Table.takeOwnership[Values](Vector(batch)))
    val source = get(Frame.values[Values](reference))
    val query = source
      .groupBy(_ => EmptyTuple)
      .aggregate(row => Tuple1(Aggregate.mean(row.col("value")).as("avg")))
    try
      val collected = ReferenceInterpreter
        .prepare(query.plan, ReferenceSources.empty.bind(reference, table))
        .collect[(avg: Option[Double])]
      collected.foreach: output =>
        // IEEE sum/count over [+Inf, 1.0] is +Infinity, not NaN.
        assertEquals(
          output.batches.head.columns(0).scalar(0),
          Right(ScalarValue.Float64(Double.PositiveInfinity): ScalarValue)
        )
        output.close()
    finally table.close()
