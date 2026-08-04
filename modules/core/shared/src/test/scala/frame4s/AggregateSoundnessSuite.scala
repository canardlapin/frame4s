package frame4s

class AggregateSoundnessSuite extends munit.FunSuite:
  test("every reduction has one truthful optional result schema"):
    val errors = compileErrors("""
      import frame4s.*

      type Required = (i: Int, l: Long, f: Float, d: Double, text: String)
      type NullableInput = (
          i: Option[Int],
          l: Option[Long],
          f: Option[Float],
          d: Option[Double],
          text: Option[String]
      )
      type Result = (
          n: Long,
          sumI: Option[Int],
          sumL: Option[Long],
          sumF: Option[Float],
          sumD: Option[Double],
          meanI: Option[Double],
          meanL: Option[Double],
          meanF: Option[Double],
          meanD: Option[Double],
          varianceI: Option[Double],
          varianceL: Option[Double],
          varianceF: Option[Double],
          varianceD: Option[Double],
          stddevI: Option[Double],
          stddevL: Option[Double],
          stddevF: Option[Double],
          stddevD: Option[Double],
          minI: Option[Int],
          minL: Option[Long],
          minF: Option[Float],
          minD: Option[Double],
          minText: Option[String],
          maxI: Option[Int],
          maxL: Option[Long],
          maxF: Option[Float],
          maxD: Option[Double],
          maxText: Option[String]
      )

      def required(frame: Frame[Required]): Frame[Result] =
        frame.groupBy(_ => EmptyTuple).aggregate: row =>
          (
            Aggregate.count.as("n"),
            Aggregate.sum(row.col("i")).as("sumI"),
            Aggregate.sum(row.col("l")).as("sumL"),
            Aggregate.sum(row.col("f")).as("sumF"),
            Aggregate.sum(row.col("d")).as("sumD"),
            Aggregate.mean(row.col("i")).as("meanI"),
            Aggregate.mean(row.col("l")).as("meanL"),
            Aggregate.mean(row.col("f")).as("meanF"),
            Aggregate.mean(row.col("d")).as("meanD"),
            Aggregate.variancePop(row.col("i")).as("varianceI"),
            Aggregate.variancePop(row.col("l")).as("varianceL"),
            Aggregate.variancePop(row.col("f")).as("varianceF"),
            Aggregate.variancePop(row.col("d")).as("varianceD"),
            Aggregate.stddevPop(row.col("i")).as("stddevI"),
            Aggregate.stddevPop(row.col("l")).as("stddevL"),
            Aggregate.stddevPop(row.col("f")).as("stddevF"),
            Aggregate.stddevPop(row.col("d")).as("stddevD"),
            Aggregate.min(row.col("i")).as("minI"),
            Aggregate.min(row.col("l")).as("minL"),
            Aggregate.min(row.col("f")).as("minF"),
            Aggregate.min(row.col("d")).as("minD"),
            Aggregate.min(row.col("text")).as("minText"),
            Aggregate.max(row.col("i")).as("maxI"),
            Aggregate.max(row.col("l")).as("maxL"),
            Aggregate.max(row.col("f")).as("maxF"),
            Aggregate.max(row.col("d")).as("maxD"),
            Aggregate.max(row.col("text")).as("maxText")
          )

      def nullable(frame: Frame[NullableInput]): Frame[Result] =
        frame.groupBy(_ => EmptyTuple).aggregate: row =>
          (
            Aggregate.count.as("n"),
            Aggregate.sum(row.col("i")).as("sumI"),
            Aggregate.sum(row.col("l")).as("sumL"),
            Aggregate.sum(row.col("f")).as("sumF"),
            Aggregate.sum(row.col("d")).as("sumD"),
            Aggregate.mean(row.col("i")).as("meanI"),
            Aggregate.mean(row.col("l")).as("meanL"),
            Aggregate.mean(row.col("f")).as("meanF"),
            Aggregate.mean(row.col("d")).as("meanD"),
            Aggregate.variancePop(row.col("i")).as("varianceI"),
            Aggregate.variancePop(row.col("l")).as("varianceL"),
            Aggregate.variancePop(row.col("f")).as("varianceF"),
            Aggregate.variancePop(row.col("d")).as("varianceD"),
            Aggregate.stddevPop(row.col("i")).as("stddevI"),
            Aggregate.stddevPop(row.col("l")).as("stddevL"),
            Aggregate.stddevPop(row.col("f")).as("stddevF"),
            Aggregate.stddevPop(row.col("d")).as("stddevD"),
            Aggregate.min(row.col("i")).as("minI"),
            Aggregate.min(row.col("l")).as("minL"),
            Aggregate.min(row.col("f")).as("minF"),
            Aggregate.min(row.col("d")).as("minD"),
            Aggregate.min(row.col("text")).as("minText"),
            Aggregate.max(row.col("i")).as("maxI"),
            Aggregate.max(row.col("l")).as("maxL"),
            Aggregate.max(row.col("f")).as("maxF"),
            Aggregate.max(row.col("d")).as("maxD"),
            Aggregate.max(row.col("text")).as("maxText")
          )
    """)

    assertEquals(errors, "")

  test("a required reduction binding reports its optional result type"):
    val errors = compileErrors("""
      import frame4s.*

      type Input = (value: Int)

      def invalid(frame: Frame[Input]): Frame[(total: Int)] =
        frame
          .groupBy(_ => EmptyTuple)
          .aggregate(row => Tuple1(Aggregate.sum(row.col("value")).as("total")))
    """)

    assert(errors.contains("Option[Int]"))

  test("dynamic aggregate construction marks every reduction except count nullable"):
    val input = DynamicFrame
      .source(
        "aggregate-nullability",
        Vector(
          DynamicFrame.field("value", DataType.Int32),
          DynamicFrame.field("text", DataType.Utf8)
        )
      )
      .fold(error => fail(error.message), identity)
    val valueColumn = input.col("value").fold(error => fail(error.message), identity)
    val textColumn = input.col("text").fold(error => fail(error.message), identity)
    val sum = DynamicAggregate.sum(valueColumn).fold(error => fail(error.message), identity)
    val mean = DynamicAggregate.mean(valueColumn).fold(error => fail(error.message), identity)
    val variance =
      DynamicAggregate.variancePop(valueColumn).fold(error => fail(error.message), identity)
    val stddev =
      DynamicAggregate.stddevPop(valueColumn).fold(error => fail(error.message), identity)
    val grouped = input.groupBy().fold(error => fail(error.message), identity)
    val result = grouped
      .aggregate(
        "n" -> DynamicAggregate.count,
        "sum" -> sum,
        "mean" -> mean,
        "variance" -> variance,
        "stddev" -> stddev,
        "minimum" -> DynamicAggregate.min(textColumn),
        "maximum" -> DynamicAggregate.max(textColumn)
      )
      .fold(error => fail(error.message), identity)

    assertEquals(
      result.schema.fields.map(field => field.name -> field.nullable),
      Vector(
        "n" -> false,
        "sum" -> true,
        "mean" -> true,
        "variance" -> true,
        "stddev" -> true,
        "minimum" -> true,
        "maximum" -> true
      )
    )

  test("dynamic aggregates reject foreign scopes with identical or reordered schemas"):
    def frame(id: String, fields: Vector[Field]): DynamicFrame =
      DynamicFrame.source(id, fields).fold(error => fail(error.message), identity)

    val ordered = Vector(
      DynamicFrame.field("p", DataType.Int32),
      DynamicFrame.field("q", DataType.Int32)
    )
    val first = frame("aggregate-first", ordered)
    val sameShape = frame("aggregate-same-shape", ordered)
    val reordered = frame("aggregate-reordered", ordered.reverse)
    val stolenColumn = first.col("p").fold(error => fail(error.message), identity)
    val stolen = DynamicAggregate.sum(stolenColumn).fold(error => fail(error.message), identity)

    def rejected(input: DynamicFrame): Unit =
      val grouped = input.groupBy().fold(error => fail(error.message), identity)
      grouped.aggregate("stolen" -> stolen) match
        case Left(FrameError.InvalidExpressionScope(_)) => ()
        case other => fail(s"expected InvalidExpressionScope, found $other")

    rejected(sameShape)
    rejected(reordered)
