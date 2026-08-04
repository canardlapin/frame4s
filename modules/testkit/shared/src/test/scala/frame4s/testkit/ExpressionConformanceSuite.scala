package frame4s.testkit

import frame4s.*

class ExpressionConformanceSuite extends munit.FunSuite:
  private def value[E, A](result: Either[E, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def equivalent[S <: scala.NamedTuple.AnyNamedTuple](
      query: Frame[S],
      sources: ReferenceSources
  )(using SchemaDescriptor[S]): BackendReceipt =
    BackendConformance.compare(query, sources, ColumnarBackend) match
      case BackendComparison.Equivalent(_, candidate) => candidate
      case other                                      => fail(s"backends disagreed: $other")

  private def observe[S <: scala.NamedTuple.AnyNamedTuple](
      query: Frame[S],
      sources: ReferenceSources
  )(using SchemaDescriptor[S]): Either[ExecutionError, ObservedTable] =
    ReferenceBackend.execute(query, sources) match
      case BackendAttempt.Completed(result, _) => result
      case other => fail(s"reference backend did not complete: $other")

  test("mixed required and nullable expressions execute identically without fallback"):
    type Input = (
        i: Int,
        oi: Option[Int],
        f: Float,
        of: Option[Float],
        b: Boolean,
        ob: Option[Boolean],
        text: String,
        optionalText: Option[String]
    )
    type Output = (
        addLeft: Option[Int],
        addRight: Option[Int],
        negatedInt: Option[Int],
        ratio: Option[Float],
        negatedFloat: Option[Float],
        conjunction: Option[Boolean],
        disjunction: Option[Boolean],
        equal: Option[Boolean],
        less: Option[Boolean],
        textEqual: Option[Boolean],
        textLess: Option[Boolean],
        truth: Boolean,
        falsehood: Boolean,
        missing: Boolean,
        present: Boolean
    )
    val reference = value(SourceRef.values("mixed-expression-law", "mixed-expression-law"))
    val input = value(
      Table.fromRows[Input](
        Vector(
          (
            i = 2,
            oi = Some(4),
            f = 2.0f,
            of = Some(8.0f),
            b = true,
            ob = None,
            text = "b",
            optionalText = Some("a")
          ),
          (
            i = 3,
            oi = None,
            f = 4.0f,
            of = None,
            b = false,
            ob = Some(true),
            text = "a",
            optionalText = None
          )
        ),
        batchSize = 1
      )
    )
    val source = value(Frame.values[Input](reference))
    val query: Frame[Output] = source.select: row =>
      (
        (row.col("oi") + row.col("i")).as("addLeft"),
        (row.col("i") + row.col("oi")).as("addRight"),
        (-row.col("oi")).as("negatedInt"),
        (row.col("of") / row.col("f")).as("ratio"),
        (-row.col("of")).as("negatedFloat"),
        (row.col("ob") && row.col("b")).as("conjunction"),
        (row.col("b") || row.col("ob")).as("disjunction"),
        (row.col("oi") === row.col("i")).as("equal"),
        (row.col("oi") < row.col("i")).as("less"),
        (row.col("optionalText") === row.col("text")).as("textEqual"),
        (row.col("optionalText") < row.col("text")).as("textLess"),
        row.col("ob").isTrue.as("truth"),
        row.col("ob").isFalse.as("falsehood"),
        row.col("oi").isNull.as("missing"),
        row.col("oi").isNotNull.as("present")
      )

    try
      val receipt = equivalent(query, ReferenceSources.empty.bind(reference, input))
      assertEquals(receipt.fallback, None)
    finally input.close()

  test("all numeric widths preserve mixed nullability and exact values"):
    type Input = (
        i: Int,
        oi: Option[Int],
        l: Long,
        ol: Option[Long],
        f: Float,
        of: Option[Float],
        d: Double,
        od: Option[Double]
    )
    type Output = (
        iAddLeft: Option[Int],
        iAddRight: Option[Int],
        iSubtract: Option[Int],
        iMultiply: Option[Int],
        iNegate: Option[Int],
        lAddLeft: Option[Long],
        lAddRight: Option[Long],
        lSubtract: Option[Long],
        lMultiply: Option[Long],
        lNegate: Option[Long],
        fAddLeft: Option[Float],
        fAddRight: Option[Float],
        fSubtract: Option[Float],
        fMultiply: Option[Float],
        fNegate: Option[Float],
        fDivideLeft: Option[Float],
        fDivideRight: Option[Float],
        dAddLeft: Option[Double],
        dAddRight: Option[Double],
        dSubtract: Option[Double],
        dMultiply: Option[Double],
        dNegate: Option[Double],
        dDivideLeft: Option[Double],
        dDivideRight: Option[Double]
    )
    val reference = value(SourceRef.values("numeric-width-law", "numeric-width-law"))
    val input = value(
      Table.fromRows[Input](
        Vector(
          (
            i = 8,
            oi = Some(4),
            l = 9L,
            ol = Some(3L),
            f = 8.0f,
            of = Some(2.0f),
            d = 9.0,
            od = Some(3.0)
          ),
          (
            i = 8,
            oi = None,
            l = 9L,
            ol = None,
            f = 8.0f,
            of = None,
            d = 9.0,
            od = None
          )
        ),
        batchSize = 1
      )
    )
    val source = value(Frame.values[Input](reference))
    val query: Frame[Output] = source.select: row =>
      (
        (row.col("oi") + row.col("i")).as("iAddLeft"),
        (row.col("i") + row.col("oi")).as("iAddRight"),
        (row.col("oi") - row.col("i")).as("iSubtract"),
        (row.col("i") * row.col("oi")).as("iMultiply"),
        (-row.col("oi")).as("iNegate"),
        (row.col("ol") + row.col("l")).as("lAddLeft"),
        (row.col("l") + row.col("ol")).as("lAddRight"),
        (row.col("ol") - row.col("l")).as("lSubtract"),
        (row.col("l") * row.col("ol")).as("lMultiply"),
        (-row.col("ol")).as("lNegate"),
        (row.col("of") + row.col("f")).as("fAddLeft"),
        (row.col("f") + row.col("of")).as("fAddRight"),
        (row.col("of") - row.col("f")).as("fSubtract"),
        (row.col("f") * row.col("of")).as("fMultiply"),
        (-row.col("of")).as("fNegate"),
        (row.col("of") / row.col("f")).as("fDivideLeft"),
        (row.col("f") / row.col("of")).as("fDivideRight"),
        (row.col("od") + row.col("d")).as("dAddLeft"),
        (row.col("d") + row.col("od")).as("dAddRight"),
        (row.col("od") - row.col("d")).as("dSubtract"),
        (row.col("d") * row.col("od")).as("dMultiply"),
        (-row.col("od")).as("dNegate"),
        (row.col("od") / row.col("d")).as("dDivideLeft"),
        (row.col("d") / row.col("od")).as("dDivideRight")
      )

    val sources = ReferenceSources.empty.bind(reference, input)
    try
      val receipt = equivalent(query, sources)
      assertEquals(receipt.fallback, None)
      val result = value(observe(query, sources))
      assertEquals(
        result.rows,
        Vector(
          Vector(
            ScalarValue.Int32(12),
            ScalarValue.Int32(12),
            ScalarValue.Int32(-4),
            ScalarValue.Int32(32),
            ScalarValue.Int32(-4),
            ScalarValue.Int64(12L),
            ScalarValue.Int64(12L),
            ScalarValue.Int64(-6L),
            ScalarValue.Int64(27L),
            ScalarValue.Int64(-3L),
            ScalarValue.Float32(10.0f),
            ScalarValue.Float32(10.0f),
            ScalarValue.Float32(-6.0f),
            ScalarValue.Float32(16.0f),
            ScalarValue.Float32(-2.0f),
            ScalarValue.Float32(0.25f),
            ScalarValue.Float32(4.0f),
            ScalarValue.Float64(12.0),
            ScalarValue.Float64(12.0),
            ScalarValue.Float64(-6.0),
            ScalarValue.Float64(27.0),
            ScalarValue.Float64(-3.0),
            ScalarValue.Float64(3.0 / 9.0),
            ScalarValue.Float64(3.0)
          ),
          Vector.fill(24)(ScalarValue.Null)
        )
      )
    finally input.close()

  test("nullable Boolean AND and OR obey the complete SQL truth table"):
    type Input = (left: Option[Boolean], right: Option[Boolean])
    type Output = (and: Option[Boolean], or: Option[Boolean])
    val reference = value(SourceRef.values("boolean-truth-law", "boolean-truth-law"))
    val truth = Vector(Some(true), Some(false), None)
    val input = value(
      Table.fromRows[Input](
        truth.flatMap(left => truth.map(right => (left = left, right = right))),
        batchSize = 2
      )
    )
    val source = value(Frame.values[Input](reference))
    val query: Frame[Output] = source.select: row =>
      (
        (row.col("left") && row.col("right")).as("and"),
        (row.col("left") || row.col("right")).as("or")
      )
    val expected = Vector(
      Vector(ScalarValue.Bool(true), ScalarValue.Bool(true)),
      Vector(ScalarValue.Bool(false), ScalarValue.Bool(true)),
      Vector(ScalarValue.Null, ScalarValue.Bool(true)),
      Vector(ScalarValue.Bool(false), ScalarValue.Bool(true)),
      Vector(ScalarValue.Bool(false), ScalarValue.Bool(false)),
      Vector(ScalarValue.Bool(false), ScalarValue.Null),
      Vector(ScalarValue.Null, ScalarValue.Bool(true)),
      Vector(ScalarValue.Bool(false), ScalarValue.Null),
      Vector(ScalarValue.Null, ScalarValue.Null)
    )
    val sources = ReferenceSources.empty.bind(reference, input)

    try
      val receipt = equivalent(query, sources)
      assertEquals(receipt.fallback, None)
      assertEquals(value(observe(query, sources)).rows, expected)
    finally input.close()

  test("integral division preserves truncation and exact structured failures"):
    type Input = (i: Int, divisor: Int, l: Long, longDivisor: Long)
    type Output = (quotient: Int, longQuotient: Long)
    val reference = value(SourceRef.values("integral-division-law", "integral-division-law"))
    val source = value(Frame.values[Input](reference))
    val query: Frame[Output] = source.select: row =>
      (
        (row.col("i") / row.col("divisor")).as("quotient"),
        (row.col("l") / row.col("longDivisor")).as("longQuotient")
      )

    def check(rows: Vector[Input]): (BackendReceipt, Either[ExecutionError, ObservedTable]) =
      val input = value(Table.fromRows[Input](rows, batchSize = 1))
      val sources = ReferenceSources.empty.bind(reference, input)
      try (equivalent(query, sources), observe(query, sources))
      finally input.close()

    val (validReceipt, valid) = check(
      Vector((i = 7, divisor = 2, l = -7L, longDivisor = 2L))
    )
    val (zeroIntReceipt, zeroInt) = check(
      Vector((i = 1, divisor = 0, l = 1L, longDivisor = 1L))
    )
    val (zeroLongReceipt, zeroLong) = check(
      Vector((i = 1, divisor = 1, l = 1L, longDivisor = 0L))
    )
    val (overflowIntReceipt, overflowInt) = check(
      Vector((i = Int.MinValue, divisor = -1, l = 1L, longDivisor = 1L))
    )
    val (overflowLongReceipt, overflowLong) = check(
      Vector((i = 1, divisor = 1, l = Long.MinValue, longDivisor = -1L))
    )

    Vector(
      validReceipt,
      zeroIntReceipt,
      zeroLongReceipt,
      overflowIntReceipt,
      overflowLongReceipt
    ).foreach(receipt => assert(receipt.fallback.nonEmpty))
    assertEquals(
      value(valid).rows,
      Vector(Vector(ScalarValue.Int32(3), ScalarValue.Int64(-3L)))
    )
    assert(
      zeroInt match
        case Left(ExecutionError.DivisionByZero(_)) => true
        case _                                      => false
    )
    assert(
      zeroLong match
        case Left(ExecutionError.DivisionByZero(_)) => true
        case _                                      => false
    )
    assert(
      overflowInt match
        case Left(ExecutionError.IntegerOverflow(_, BinaryOperator.Divide)) => true
        case _                                                              => false
    )
    assert(
      overflowLong match
        case Left(ExecutionError.IntegerOverflow(_, BinaryOperator.Divide)) => true
        case _                                                              => false
    )

  test("checked integral negation agrees on valid values and both overflow widths"):
    type Input = (i: Int, l: Long)
    type Output = (negatedInt: Int, negatedLong: Long)
    val reference = value(SourceRef.values("integral-negation-law", "integral-negation-law"))
    val source = value(Frame.values[Input](reference))
    val query: Frame[Output] = source.select: row =>
      (
        (-row.col("i")).as("negatedInt"),
        (-row.col("l")).as("negatedLong")
      )

    def check(row: Input): (BackendReceipt, Either[ExecutionError, ObservedTable]) =
      val input = value(Table.fromRows[Input](Vector(row)))
      val sources = ReferenceSources.empty.bind(reference, input)
      try (equivalent(query, sources), observe(query, sources))
      finally input.close()

    val (validReceipt, valid) = check((i = 7, l = -9L))
    val (intReceipt, intOverflow) = check((i = Int.MinValue, l = 1L))
    val (longReceipt, longOverflow) = check((i = 1, l = Long.MinValue))

    Vector(validReceipt, intReceipt, longReceipt).foreach: receipt =>
      assertEquals(receipt.fallback, None)
    assertEquals(
      value(valid).rows,
      Vector(Vector(ScalarValue.Int32(-7), ScalarValue.Int64(9L)))
    )
    assert(
      intOverflow match
        case Left(ExecutionError.IntegerOverflow(_, BinaryOperator.Subtract)) => true
        case _                                                                => false
    )
    assert(
      longOverflow match
        case Left(ExecutionError.IntegerOverflow(_, BinaryOperator.Subtract)) => true
        case _                                                                => false
    )

  test("typed and dynamic expressions have the same plan, schema, and values"):
    type Input = (
        i: Int,
        oi: Option[Int],
        f: Double,
        of: Option[Double],
        b: Boolean,
        ob: Option[Boolean]
    )
    type Output = (
        sum: Option[Int],
        difference: Option[Int],
        product: Option[Int],
        ratio: Option[Double],
        root: Option[Double],
        equal: Option[Boolean],
        unequal: Option[Boolean],
        totalEqual: Boolean,
        ordered: Option[Boolean],
        conjunction: Option[Boolean],
        disjunction: Option[Boolean],
        truth: Boolean,
        falsehood: Boolean,
        missing: Boolean,
        present: Boolean,
        negated: Option[Int]
    )
    val reference = value(SourceRef.values("dynamic-expression-law", "dynamic-expression-law"))
    val input = value(
      Table.fromRows[Input](
        Vector(
          (i = 2, oi = Some(4), f = 2.0, of = Some(8.0), b = true, ob = None),
          (i = 3, oi = None, f = 4.0, of = None, b = false, ob = Some(true))
        )
      )
    )
    val source = value(Frame.values[Input](reference))
    val typed: Frame[Output] = source.select: row =>
      (
        (row.col("oi") + row.col("i")).as("sum"),
        (row.col("oi") - row.col("i")).as("difference"),
        (row.col("oi") * row.col("i")).as("product"),
        (row.col("of") / row.col("f")).as("ratio"),
        row.col("of").sqrt.as("root"),
        (row.col("oi") === row.col("i")).as("equal"),
        (row.col("oi") =!= row.col("i")).as("unequal"),
        row.col("oi").nullSafeEq(row.col("i")).as("totalEqual"),
        (row.col("oi") <= row.col("i")).as("ordered"),
        (row.col("ob") && row.col("b")).as("conjunction"),
        (row.col("ob") || row.col("b")).as("disjunction"),
        row.col("ob").isTrue.as("truth"),
        row.col("ob").isFalse.as("falsehood"),
        row.col("oi").isNull.as("missing"),
        row.col("oi").isNotNull.as("present"),
        (-row.col("oi")).as("negated")
      )

    val dynamic = value:
      for
        i <- source.dynamic.col("i")
        oi <- source.dynamic.col("oi")
        f <- source.dynamic.col("f")
        of <- source.dynamic.col("of")
        b <- source.dynamic.col("b")
        ob <- source.dynamic.col("ob")
        sum <- oi + i
        difference <- oi - i
        product <- oi * i
        ratio <- of / f
        root <- of.sqrt
        equal <- oi === i
        unequal <- oi =!= i
        totalEqual <- oi.nullSafeEq(i)
        ordered <- oi <= i
        conjunction <- ob && b
        disjunction <- ob || b
        truth <- ob.isTrue
        falsehood <- ob.isFalse
        negated <- oi.negate
        selected <- source.dynamic.select(
          "sum" -> sum,
          "difference" -> difference,
          "product" -> product,
          "ratio" -> ratio,
          "root" -> root,
          "equal" -> equal,
          "unequal" -> unequal,
          "totalEqual" -> totalEqual,
          "ordered" -> ordered,
          "conjunction" -> conjunction,
          "disjunction" -> disjunction,
          "truth" -> truth,
          "falsehood" -> falsehood,
          "missing" -> oi.isNull,
          "present" -> oi.isNotNull,
          "negated" -> negated
        )
        output <- selected.typed[Output]
      yield output

    val sources = ReferenceSources.empty.bind(reference, input)
    try
      assertEquals(dynamic.plan, typed.plan)
      assertEquals(dynamic.schema, typed.schema)
      assertEquals(dynamic.explain, typed.explain)
      assertEquals(
        ReferenceBackend.execute(dynamic, sources),
        ReferenceBackend.execute(typed, sources)
      )
    finally input.close()

  test("dynamic operations reject unsupported and mismatched physical types structurally"):
    val text = DynamicExpr.literal(LiteralValue.checkedUtf8("text"))
    val int = DynamicExpr.literal(LiteralValue.Int32(1))
    val bool = DynamicExpr.literal(LiteralValue.Bool(true))

    assertEquals(
      text + text,
      Left(
        FrameError.UnsupportedBinaryExpression(
          BinaryOperator.Add,
          DataType.Utf8,
          DataType.Utf8
        )
      )
    )
    assertEquals(
      int === text,
      Left(
        FrameError.UnsupportedBinaryExpression(
          BinaryOperator.Equal,
          DataType.Int32,
          DataType.Utf8
        )
      )
    )
    assertEquals(
      bool.negate,
      Left(FrameError.UnsupportedUnaryExpression(UnaryOperator.Negate, DataType.Bool))
    )
    assertEquals(
      int.isFalse,
      Left(FrameError.UnsupportedUnaryExpression(UnaryOperator.IsFalse, DataType.Int32))
    )
