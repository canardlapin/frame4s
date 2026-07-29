package frame4s

import scala.compiletime.testing.typeCheckErrors

class ErgonomicExpressionSuite extends munit.FunSuite:
  type Input = (id: Int, score: Option[Double], value: Double, label: String)
  type Selected = (label: String, score: Option[Double])

  private def get[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def source: Frame[Input] =
    get(Frame.source[Input]("input"))

  private def firstDiagnostic(
      errors: List[scala.compiletime.testing.Error]
  ): String =
    errors.headOption.fold(fail("expected a compile-time diagnostic"))(_.message)

  private def assertUserFacingFirst(message: String, expected: String): Unit =
    val expectedIndex = message.indexOf(expected)
    val internalIndex = message.indexOf("I found:")
    assert(expectedIndex >= 0, message)
    assert(internalIndex < 0 || expectedIndex < internalIndex, message)

  test("exact scalar operands have the same plans as explicit literals"):
    val frame = source
    val concise = Vector(
      frame.filter(row => row.col("id") === 1).plan,
      frame.filter(row => row.col("id") =!= 1).plan,
      frame.filter(row => row.col("id") < 1).plan,
      frame.filter(row => row.col("id") <= 1).plan,
      frame.filter(row => row.col("id") > 1).plan,
      frame.filter(row => row.col("id") >= 1).plan,
      frame.filter(row => row.col("id").nullSafeEq(1)).plan,
      frame
        .select(row => Tuple1((row.col("id") + 1).as("result")))
        .plan,
      frame
        .select(row => Tuple1((row.col("id") - 1).as("result")))
        .plan,
      frame
        .select(row => Tuple1((row.col("id") * 2).as("result")))
        .plan,
      frame
        .select(row => Tuple1((row.col("value") / 2.0).as("result")))
        .plan
    )
    val expanded = Vector(
      frame.filter(row => row.col("id") === Expr.literal(1)).plan,
      frame.filter(row => row.col("id") =!= Expr.literal(1)).plan,
      frame.filter(row => row.col("id") < Expr.literal(1)).plan,
      frame.filter(row => row.col("id") <= Expr.literal(1)).plan,
      frame.filter(row => row.col("id") > Expr.literal(1)).plan,
      frame.filter(row => row.col("id") >= Expr.literal(1)).plan,
      frame.filter(row => row.col("id").nullSafeEq(Expr.literal(1))).plan,
      frame
        .select(row => Tuple1((row.col("id") + Expr.literal(1)).as("result")))
        .plan,
      frame
        .select(row => Tuple1((row.col("id") - Expr.literal(1)).as("result")))
        .plan,
      frame
        .select(row => Tuple1((row.col("id") * Expr.literal(2)).as("result")))
        .plan,
      frame
        .select(row => Tuple1((row.col("value") / Expr.literal(2.0)).as("result")))
        .plan
    )

    assertEquals(concise, expanded)

  test("raw columns retain names while computed expressions still require aliases"):
    val selected: Frame[Selected] =
      source.select(row => (row.col("label"), row.col("score")))
    val grouped: Frame[(label: String, n: Long)] =
      source
        .groupBy(row => Tuple1(row.col("label")))
        .aggregate(_ => Tuple1(Aggregate.count.as("n")))

    assertEquals(selected.schema.fields.map(_.name), Vector("label", "score"))
    assertEquals(grouped.schema.fields.map(_.name), Vector("label", "n"))

    val message = firstDiagnostic(typeCheckErrors("""
      import frame4s.*
      type Input = (id: Int)
      val frame = Frame.source[Input]("input").toOption.get
      frame.select(row => Tuple1(row.col("id") + 1))
    """))
    assert(message.contains("Add .as"), message)
    assert(!message.contains("match type"), message)

  test("nullable exact operands retain Option semantics"):
    val frame = source
    val concise = frame.filter(row => (row.col("score") > Some(0.0)).isTrue)
    val expanded =
      frame.filter(row => (row.col("score") > Expr.literal[Option[Double]](Some(0.0))).isTrue)

    assertEquals(concise.plan, expanded.plan)
    assertEquals(concise.schema, expanded.schema)
    assertEquals(concise.explain, expanded.explain)

  test("concise and expanded forms have oracle result, ordering, and failure parity"):
    val reference = get(SourceRef.values("input", "input"))
    val frame = get(Frame.values[Input](reference))
    val input = get(
      Table.fromRows[Input](
        Vector(
          (id = 2, score = Some(2.5), value = 4.0, label = "second"),
          (id = 0, score = None, value = 1.0, label = "filtered"),
          (id = 1, score = Some(1.5), value = 2.0, label = "first")
        )
      )
    )
    val sources = ReferenceSources.empty.bind(reference, input)
    val filtered = frame.filter(row => row.col("id") > 0)
    val concise: Frame[Selected] =
      filtered.select(row => (row.col("label"), row.col("score")))
    val expanded: Frame[Selected] =
      filtered.select(row => (row.col("label").as("label"), row.col("score").as("score")))

    val conciseTable =
      get(ReferenceInterpreter.prepare(concise.plan, sources).collect[Selected])
    val expandedTable =
      get(ReferenceInterpreter.prepare(expanded.plan, sources).collect[Selected])
    try
      assertEquals(concise.plan, expanded.plan)
      assertEquals(conciseTable.row(0), expandedTable.row(0))
      assertEquals(conciseTable.row(1), expandedTable.row(1))
      assertEquals(conciseTable.show(), expandedTable.show())
      assertEquals(
        conciseTable.column("label"),
        Right(Vector("second", "first"))
      )
    finally
      conciseTable.close()
      expandedTable.close()

    val conciseFailure = frame.select: row =>
      Tuple1((row.col("id") + Int.MaxValue).as("result"))
    val expandedFailure = frame.select: row =>
      Tuple1((row.col("id") + Expr.literal(Int.MaxValue)).as("result"))
    val conciseError =
      ReferenceInterpreter.prepare(conciseFailure.plan, sources).collect[(result: Int)]
    val expandedError =
      ReferenceInterpreter.prepare(expandedFailure.plan, sources).collect[(result: Int)]
    try
      assertEquals(conciseFailure.plan, expandedFailure.plan)
      assertEquals(conciseError.left.map(_.message), expandedError.left.map(_.message))
      assert(conciseError.isLeft)
    finally
      conciseError.foreach(_.close())
      expandedError.foreach(_.close())
      input.close()

  test("direct raw null is rejected by every literal and operand family"):
    val diagnostics = List(
      typeCheckErrors("""
        import frame4s.*
        def invalid[Origin](expression: ExprOf[Int, Origin]) = expression === null
      """),
      typeCheckErrors("""
        import frame4s.*
        def invalid[Origin](expression: ExprOf[Int, Origin]) = expression =!= null
      """),
      typeCheckErrors("""
        import frame4s.*
        def invalid[Origin](expression: ExprOf[Int, Origin]) = expression.nullSafeEq(null)
      """),
      typeCheckErrors("""
        import frame4s.*
        def invalid[Origin](expression: ExprOf[Int, Origin]) = expression < null
      """),
      typeCheckErrors("""
        import frame4s.*
        def invalid[Origin](expression: ExprOf[Int, Origin]) = expression <= null
      """),
      typeCheckErrors("""
        import frame4s.*
        def invalid[Origin](expression: ExprOf[Int, Origin]) = expression > null
      """),
      typeCheckErrors("""
        import frame4s.*
        def invalid[Origin](expression: ExprOf[Int, Origin]) = expression >= null
      """),
      typeCheckErrors("""
        import frame4s.*
        def invalid[Origin](expression: ExprOf[Int, Origin]) = expression + null
      """),
      typeCheckErrors("""
        import frame4s.*
        def invalid[Origin](expression: ExprOf[Int, Origin]) = expression - null
      """),
      typeCheckErrors("""
        import frame4s.*
        def invalid[Origin](expression: ExprOf[Int, Origin]) = expression * null
      """),
      typeCheckErrors("""
        import frame4s.*
        def invalid[Origin](expression: ExprOf[Double, Origin]) = expression / null
      """)
    )

    diagnostics.foreach: errors =>
      val message = firstDiagnostic(errors)
      assert(message.contains("Raw null is not a typed column value"), message)
      assert(!message.contains("match type"), message)

    val literalMessage = firstDiagnostic(typeCheckErrors("""
      import frame4s.*
      Expr.literal(null)
    """))
    assert(literalMessage.contains("Raw null is not a typed column value"), literalMessage)

  test("wrong exact operands and numeric widening fail before internal evidence"):
    val wrong = firstDiagnostic(typeCheckErrors("""
      import frame4s.*
      type Input = (id: Int)
      val frame = Frame.source[Input]("input").toOption.get
      frame.filter(row => row.col("id") > "1")
    """))
    assertUserFacingFirst(wrong, "Scalar operand has type String; expected Int")

    val widened = firstDiagnostic(typeCheckErrors("""
      import frame4s.*
      type Input = (id: Int)
      val frame = Frame.source[Input]("input").toOption.get
      frame.filter(row => row.col("id") > 1L)
    """))
    assertUserFacingFirst(widened, "Scalar operand has type Long; expected Int")
