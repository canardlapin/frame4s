package frame4s

class ColumnarInterpreterSuite extends munit.FunSuite:
  private def value[E, A](result: Either[E, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def reference(id: String): SourceRef =
    value(SourceRef.values(id, id))

  private def table[S <: scala.NamedTuple.AnyNamedTuple](
      rows: Vector[S],
      batchSize: Int = 2
  )(using SchemaDescriptor[S], RowCodec[S]): Table[S] =
    value(Table.fromRows(rows, batchSize))

  private def completed(run: ColumnarRun): ColumnarResult =
    run.result.fold(error => fail(error.message), identity)

  test("direct primitive and nullable projections use the columnar scan kernel"):
    type Input = (id: Int, value: Option[Double], label: String)
    type Output = (id: Int, value: Option[Double])
    val ref = reference("columnar-scan")
    val input = table[Input](
      Vector(
        (id = 1, value = Some(1.5), label = "a"),
        (id = 2, value = None, label = "b"),
        (id = 3, value = Some(-0.0), label = "c")
      )
    )
    val source = value(Frame.values[Input](ref))
    val query: Frame[Output] =
      source.select(row => (row.col("id").as("id"), row.col("value").as("value")))
    val run = ColumnarInterpreter
      .prepare(query.plan, ReferenceSources.empty.bind(ref, input))
      .run()
    val result = completed(run)
    try
      assertEquals(run.receipt.fallback, None)
      assert(run.receipt.physicalPlan.contains("ScanProject"))
      assertEquals(
        result.rows,
        Right(
          Vector(
            Vector(ScalarValue.Int32(1), ScalarValue.Float64(1.5)),
            Vector(ScalarValue.Int32(2), ScalarValue.Null),
            Vector(ScalarValue.Int32(3), ScalarValue.Float64(-0.0))
          )
        )
      )
      assertEquals(result.rowCount, 3L)
      assertEquals(result.order, OrderGuarantee.Stable)
    finally
      result.close()
      input.close()

  test("fused filter, projection, and checked arithmetic preserve batch boundaries"):
    type Input = (id: Int, ignored: String)
    type Output = (id: Int, next: Int)
    val ref = reference("columnar-fused")
    val input = table[Input](
      Vector.tabulate(9)(index => (id = index, ignored = s"row-$index")),
      batchSize = 2
    )
    val source = value(Frame.values[Input](ref))
    val query: Frame[Output] =
      source
        .filter(row => row.col("id") >= Expr.literal(4))
        .select: row =>
          (
            row.col("id").as("id"),
            (row.col("id") + Expr.literal(1)).as("next")
          )
    val run = ColumnarInterpreter
      .prepare(query.plan, ReferenceSources.empty.bind(ref, input))
      .run()
    val result = completed(run)
    try
      assertEquals(run.receipt.fallback, None)
      assert(run.receipt.physicalPlan.contains("FusedFilterProjectCheckedInt32"))
      assertEquals(
        result.rows,
        Right(
          Vector.tabulate(5)(index =>
            Vector(ScalarValue.Int32(index + 4), ScalarValue.Int32(index + 5))
          )
        )
      )
      assertEquals(result.rowCount, 5L)
    finally
      result.close()
      input.close()

  test("fused checked arithmetic returns the oracle's exact overflow error"):
    type Input = (id: Int)
    type Output = (result: Int)
    val ref = reference("columnar-overflow")
    val input = table[Input](Vector((id = Int.MaxValue)))
    val source = value(Frame.values[Input](ref))
    val query: Frame[Output] =
      source
        .filter(row => row.col("id") >= Expr.literal(Int.MinValue))
        .select(row => Tuple1((row.col("id") + Expr.literal(1)).as("result")))
    val run = ColumnarInterpreter
      .prepare(query.plan, ReferenceSources.empty.bind(ref, input))
      .run()
    val expressionId = query.plan match
      case LogicalPlan.Project(_, Vector(named), _) => named.expression.id
      case other                                    => fail(s"unexpected plan: $other")
    try
      assertEquals(
        run.result,
        Left(ExecutionError.IntegerOverflow(expressionId, BinaryOperator.Add))
      )
      assertEquals(run.receipt.fallback, None)
    finally input.close()

  test("typed filter and withColumn chains compile as a general Float64 expression pipeline"):
    type Input = (name: String, height: Double, mass: Double)
    type Output = (name: String, heightM: Double, bmi: Double)
    val ref = reference("columnar-expression-pipeline-float64")
    val input = table[Input](
      Vector(
        (name = "short", height = 160.0, mass = 60.0),
        (name = "keep", height = 180.0, mass = 81.0),
        (name = "edge", height = 170.0, mass = 68.0)
      ),
      batchSize = 1
    )
    val source = value(Frame.values[Input](ref))
    val withHeight = source
      .filter(row => row.col("height") >= 170.0)
      .withColumn("heightM")(row => row.col("height") / 100.0)
    val withBmi = withHeight.withColumn("bmi"): row =>
      row.col("mass") / (row.col("heightM") * row.col("heightM"))
    val query: Frame[Output] =
      withBmi.select(row => (row.col("name"), row.col("heightM"), row.col("bmi")))
    val run = ColumnarInterpreter
      .prepare(query.plan, ReferenceSources.empty.bind(ref, input))
      .run()
    val result = completed(run)
    try
      assertEquals(run.receipt.fallback, None)
      assert(run.receipt.physicalPlan.contains("ExpressionPipeline"))
      assertEquals(
        result.rows,
        Right(
          Vector(
            Vector(
              ScalarValue.Utf8("keep"),
              ScalarValue.Float64(1.8),
              ScalarValue.Float64(81.0 / (1.8 * 1.8))
            ),
            Vector(
              ScalarValue.Utf8("edge"),
              ScalarValue.Float64(1.7),
              ScalarValue.Float64(68.0 / (1.7 * 1.7))
            )
          )
        )
      )
    finally
      result.close()
      input.close()

  test("general filter preserves nullable predicates and SQL isTrue semantics"):
    type Input = (id: Int, height: Option[Double])
    val ref = reference("columnar-expression-pipeline-null-filter")
    val input = table[Input](
      Vector(
        (id = 1, height = None),
        (id = 2, height = Some(169.0)),
        (id = 3, height = Some(170.0))
      ),
      batchSize = 1
    )
    val query = value(Frame.values[Input](ref)).filter: row =>
      (row.col("height") >= Expr.literal(Option(170.0))).isTrue
    val run = ColumnarInterpreter
      .prepare(query.plan, ReferenceSources.empty.bind(ref, input))
      .run()
    val result = completed(run)
    try
      assertEquals(run.receipt.fallback, None)
      assert(run.receipt.physicalPlan.contains("ExpressionPipeline"))
      assertEquals(
        result.rows,
        Right(
          Vector(
            Vector(ScalarValue.Int32(3), ScalarValue.Float64(170.0))
          )
        )
      )
    finally
      result.close()
      input.close()

  test("general filter compiles UTF-8 equality and boolean conjunction"):
    type Input = (name: String, skin: String, eyes: String)
    type Output = (name: String)
    val ref = reference("columnar-expression-pipeline-utf8")
    val input = table[Input](
      Vector(
        (name = "one", skin = "light", eyes = "brown"),
        (name = "two", skin = "light", eyes = "blue"),
        (name = "three", skin = "dark", eyes = "brown"),
        (name = "四", skin = "light", eyes = "brown")
      ),
      batchSize = 1
    )
    val query: Frame[Output] = value(Frame.values[Input](ref))
      .filter(row => (row.col("skin") === "light") && (row.col("eyes") === "brown"))
      .select(row => Tuple1(row.col("name")))
    val run = ColumnarInterpreter
      .prepare(query.plan, ReferenceSources.empty.bind(ref, input))
      .run()
    val result = completed(run)
    try
      assertEquals(run.receipt.fallback, None)
      assert(run.receipt.physicalPlan.contains("ExpressionPipeline"))
      assertEquals(
        result.rows,
        Right(
          Vector(
            Vector(ScalarValue.Utf8("one")),
            Vector(ScalarValue.Utf8("四"))
          )
        )
      )
    finally
      result.close()
      input.close()

  test("general filter keeps dictionary-encoded direct projections detached"):
    type Input = (id: Int, word: String)
    type Output = (word: String)
    val descriptor = summon[SchemaDescriptor[Input]]
    val ids = value(ColumnArray.int32(Array(1, 2, 3)))
    val indices = value(ColumnArray.int32(Array(0, 1, 0)))
    val dictionaryValues = value(ColumnArray.utf8(Array("alpha", "beta")))
    val words = value(ColumnArray.dictionary(indices, dictionaryValues))
    val batch = RecordBatch(descriptor.schema, Vector(ids, words)) match
      case Right(value) => value
      case Left(error)  =>
        ids.close()
        words.close()
        fail(error.message)
    val input = value(Table[Input](Vector(batch)))
    val ref = reference("columnar-expression-pipeline-dictionary")
    val query: Frame[Output] = value(Frame.values[Input](ref))
      .filter(row => row.col("id") >= 2)
      .select(row => Tuple1(row.col("word")))
    val run = ColumnarInterpreter
      .prepare(query.plan, ReferenceSources.empty.bind(ref, input))
      .run()
    val result = completed(run)
    input.close()
    try
      assertEquals(run.receipt.fallback, None)
      assert(run.receipt.physicalPlan.contains("ExpressionPipeline"))
      assertEquals(
        result.rows,
        Right(
          Vector(
            Vector(ScalarValue.Utf8("beta")),
            Vector(ScalarValue.Utf8("alpha"))
          )
        )
      )
    finally result.close()

  test("general Int32 filter and withColumn preserve boolean composition and checked arithmetic"):
    type Input = (id: Int, label: String)
    type Output = (id: Int, label: String, score: Int)
    val ref = reference("columnar-expression-pipeline-int32")
    val input = table[Input](
      Vector.tabulate(5)(index => (id = index, label = s"row-$index")),
      batchSize = 2
    )
    val query: Frame[Output] = value(Frame.values[Input](ref))
      .filter(row => (row.col("id") >= 1) && (row.col("id") < 4))
      .withColumn("score")(row => row.col("id") * 3 - 1)
    val run = ColumnarInterpreter
      .prepare(query.plan, ReferenceSources.empty.bind(ref, input))
      .run()
    val result = completed(run)
    try
      assertEquals(run.receipt.fallback, None)
      assert(run.receipt.physicalPlan.contains("ExpressionPipeline"))
      assertEquals(
        result.rows,
        Right(
          Vector(
            Vector(ScalarValue.Int32(1), ScalarValue.Utf8("row-1"), ScalarValue.Int32(2)),
            Vector(ScalarValue.Int32(2), ScalarValue.Utf8("row-2"), ScalarValue.Int32(5)),
            Vector(ScalarValue.Int32(3), ScalarValue.Utf8("row-3"), ScalarValue.Int32(8))
          )
        )
      )
    finally
      result.close()
      input.close()

  test("general verb compiler covers Int64 and Float32 expressions"):
    type Input = (count: Long, ratio: Float)
    type Output = (count: Long, ratio: Float, scaled: Float, total: Long)
    val ref = reference("columnar-expression-pipeline-int64-float32")
    val input = table[Input](
      Vector(
        (count = 1L, ratio = 1.0f),
        (count = 2L, ratio = 2.0f),
        (count = 3L, ratio = 4.0f)
      ),
      batchSize = 1
    )
    val filtered = value(Frame.values[Input](ref)).filter: row =>
      (row.col("count") >= 2L) && (row.col("ratio") < 3.0f)
    val withScaled = filtered.withColumn("scaled")(row => row.col("ratio") / 2.0f)
    val query: Frame[Output] =
      withScaled.withColumn("total")(row => row.col("count") * 2L)
    val run = ColumnarInterpreter
      .prepare(query.plan, ReferenceSources.empty.bind(ref, input))
      .run()
    val result = completed(run)
    try
      assertEquals(run.receipt.fallback, None)
      assert(run.receipt.physicalPlan.contains("ExpressionPipeline"))
      assertEquals(
        result.rows,
        Right(
          Vector(
            Vector(
              ScalarValue.Int64(2L),
              ScalarValue.Float32(2.0f),
              ScalarValue.Float32(1.0f),
              ScalarValue.Int64(4L)
            )
          )
        )
      )
    finally
      result.close()
      input.close()

  test("unsupported logical shapes fall back for the whole plan with a receipt"):
    type Input = (id: Int, label: String)
    val ref = reference("columnar-fallback")
    val input = table[Input](Vector((id = 1, label = "a"), (id = 2, label = "b")))
    val source = value(Frame.values[Input](ref))
    val query = value(source.filter(row => row.col("id") >= Expr.literal(2)).limit(1))
    val execution =
      ColumnarInterpreter.prepare(query.plan, ReferenceSources.empty.bind(ref, input))
    val run = execution.run()
    val result = completed(run)
    try
      assert(execution.physicalExplain.contains("ReferenceWholePlan"))
      assertEquals(run.receipt.fallback, Some("unsupported logical shape Limit"))
      assert(run.receipt.physicalPlan.contains("oracle=ReferenceExecution"))
      assertEquals(
        result.rows,
        Right(Vector(Vector(ScalarValue.Int32(2), ScalarValue.Utf8("b"))))
      )
    finally
      result.close()
      input.close()

  test("hash aggregation preserves first-key order, null aggregates, and population moments"):
    type Input = (group: String, value: Option[Double])
    type Output = (
        group: String,
        n: Long,
        sum: Option[Double],
        mean: Option[Double],
        variancePop: Option[Double]
    )
    val ref = reference("columnar-aggregate")
    val input = table[Input](
      Vector(
        (group = "a", value = Some(1.0)),
        (group = "b", value = None),
        (group = "a", value = Some(3.0))
      ),
      batchSize = 1
    )
    val source = value(Frame.values[Input](ref))
    val query: Frame[Output] =
      source
        .groupBy(row => Tuple1(row.col("group").as("group")))
        .aggregate: row =>
          (
            Aggregate.count.as("n"),
            Aggregate.sum(row.col("value")).as("sum"),
            Aggregate.mean(row.col("value")).as("mean"),
            Aggregate.variancePop(row.col("value")).as("variancePop")
          )
    val run = ColumnarInterpreter
      .prepare(query.plan, ReferenceSources.empty.bind(ref, input))
      .run()
    val result = completed(run)
    try
      assertEquals(run.receipt.fallback, None)
      assert(run.receipt.physicalPlan.contains("HashAggregate"))
      assertEquals(
        result.rows,
        Right(
          Vector(
            Vector(
              ScalarValue.Utf8("a"),
              ScalarValue.Int64(2L),
              ScalarValue.Float64(4.0),
              ScalarValue.Float64(2.0),
              ScalarValue.Float64(1.0)
            ),
            Vector(
              ScalarValue.Utf8("b"),
              ScalarValue.Int64(1L),
              ScalarValue.Null,
              ScalarValue.Null,
              ScalarValue.Null
            )
          )
        )
      )
    finally
      result.close()
      input.close()

  test("hash aggregation preserves sum-only null and ordering semantics"):
    type Input = (group: String, value: Option[Double])
    type Output = (group: String, sum: Option[Double])
    val ref = reference("columnar-aggregate-sum-only")
    val input = table[Input](
      Vector(
        (group = "a", value = Some(1.0)),
        (group = "b", value = None),
        (group = "a", value = Some(3.0)),
        (group = "long-key-abcdefgh", value = Some(2.0)),
        (group = "long-key-abcdefgh", value = Some(5.0))
      ),
      batchSize = 1
    )
    val source = value(Frame.values[Input](ref))
    val query: Frame[Output] =
      source
        .groupBy(row => Tuple1(row.col("group").as("group")))
        .aggregate(row => Tuple1(Aggregate.sum(row.col("value")).as("sum")))
    val run = ColumnarInterpreter
      .prepare(query.plan, ReferenceSources.empty.bind(ref, input))
      .run()
    val result = completed(run)
    try
      assertEquals(run.receipt.fallback, None)
      assertEquals(
        result.rows,
        Right(
          Vector(
            Vector(ScalarValue.Utf8("a"), ScalarValue.Float64(4.0)),
            Vector(ScalarValue.Utf8("b"), ScalarValue.Null),
            Vector(ScalarValue.Utf8("long-key-abcdefgh"), ScalarValue.Float64(7.0))
          )
        )
      )
    finally
      result.close()
      input.close()

  test("projected multi-key and multi-measure aggregation uses the general kernel"):
    type Input = (
        species: Option[String],
        sex: String,
        height: Option[Double],
        mass: Option[Double],
        ignored: Int
    )
    type Selected = (
        mass: Option[Double],
        species: Option[String],
        height: Option[Double],
        sex: String
    )
    type Output = (
        species: Option[String],
        sex: String,
        n: Long,
        meanHeight: Option[Double],
        meanMass: Option[Double]
    )
    val ref = reference("columnar-general-aggregate")
    val input = table[Input](
      Vector(
        (
          species = Some("human"),
          sex = "female",
          height = Some(160.0),
          mass = Some(55.0),
          ignored = 1
        ),
        (
          species = None,
          sex = "unknown",
          height = None,
          mass = Some(20.0),
          ignored = 2
        ),
        (
          species = Some("human"),
          sex = "female",
          height = Some(180.0),
          mass = None,
          ignored = 3
        ),
        (
          species = Some("human"),
          sex = "male",
          height = Some(190.0),
          mass = Some(90.0),
          ignored = 4
        )
      ),
      batchSize = 1
    )
    val selected: Frame[Selected] = value(Frame.values[Input](ref)).select: row =>
      (
        row.col("mass"),
        row.col("species"),
        row.col("height"),
        row.col("sex")
      )
    val query: Frame[Output] = selected
      .groupBy(row => (row.col("species"), row.col("sex")))
      .aggregate: row =>
        (
          Aggregate.count.as("n"),
          Aggregate.mean(row.col("height")).as("meanHeight"),
          Aggregate.mean(row.col("mass")).as("meanMass")
        )
    val run = ColumnarInterpreter
      .prepare(query.plan, ReferenceSources.empty.bind(ref, input))
      .run()
    val result = completed(run)
    try
      assertEquals(run.receipt.fallback, None)
      assert(run.receipt.physicalPlan.contains("HashAggregate[General,keys=2,measures=2]"))
      assertEquals(
        result.rows,
        Right(
          Vector(
            Vector(
              ScalarValue.Utf8("human"),
              ScalarValue.Utf8("female"),
              ScalarValue.Int64(2L),
              ScalarValue.Float64(170.0),
              ScalarValue.Float64(55.0)
            ),
            Vector(
              ScalarValue.Null,
              ScalarValue.Utf8("unknown"),
              ScalarValue.Int64(1L),
              ScalarValue.Null,
              ScalarValue.Float64(20.0)
            ),
            Vector(
              ScalarValue.Utf8("human"),
              ScalarValue.Utf8("male"),
              ScalarValue.Int64(1L),
              ScalarValue.Float64(190.0),
              ScalarValue.Float64(90.0)
            )
          )
        )
      )
    finally
      result.close()
      input.close()

  test("optimized sums preserve a first negative zero without changing mean semantics"):
    type Input = (id: Int, group: String, value: Option[Double])
    type IntOutput = (id: Int, sum: Option[Double], mean: Option[Double])
    type Utf8Output = (group: String, sum: Option[Double])
    val ref = reference("columnar-aggregate-negative-zero")
    val input = table[Input](Vector((id = 1, group = "g", value = Some(-0.0))))
    val source = value(Frame.values[Input](ref))
    val intQuery: Frame[IntOutput] =
      source
        .groupBy(row => Tuple1(row.col("id").as("id")))
        .aggregate: row =>
          (
            Aggregate.sum(row.col("value")).as("sum"),
            Aggregate.mean(row.col("value")).as("mean")
          )
    val utf8Query: Frame[Utf8Output] =
      source
        .groupBy(row => Tuple1(row.col("group").as("group")))
        .aggregate(row => Tuple1(Aggregate.sum(row.col("value")).as("sum")))
    val sources = ReferenceSources.empty.bind(ref, input)
    val intResult = completed(ColumnarInterpreter.prepare(intQuery.plan, sources).run())
    val utf8Result = completed(ColumnarInterpreter.prepare(utf8Query.plan, sources).run())
    try
      intResult.rows match
        case Right(Vector(Vector(_, ScalarValue.Float64(sum), ScalarValue.Float64(mean)))) =>
          assertEquals(
            java.lang.Double.doubleToRawLongBits(sum),
            java.lang.Double.doubleToRawLongBits(-0.0)
          )
          assertEquals(
            java.lang.Double.doubleToRawLongBits(mean),
            java.lang.Double.doubleToRawLongBits(0.0)
          )
        case other => fail(s"unexpected Int32 aggregate result: $other")
      utf8Result.rows match
        case Right(Vector(Vector(_, ScalarValue.Float64(sum)))) =>
          assertEquals(
            java.lang.Double.doubleToRawLongBits(sum),
            java.lang.Double.doubleToRawLongBits(-0.0)
          )
        case other => fail(s"unexpected UTF-8 aggregate result: $other")
    finally
      intResult.close()
      utf8Result.close()
      input.close()

  test("required Int32 grouping uses primitive accumulators without changing moments"):
    type Input = (id: Int, value: Option[Double])
    type Output = (
        id: Int,
        n: Long,
        sum: Option[Double],
        mean: Option[Double],
        variancePop: Option[Double]
    )
    val ref = reference("columnar-aggregate-int32")
    val input = table[Input](
      Vector(
        (id = 2, value = Some(1.0)),
        (id = 1, value = None),
        (id = 2, value = Some(3.0)),
        (id = 1, value = Some(4.0))
      ),
      batchSize = 1
    )
    val query: Frame[Output] =
      value(Frame.values[Input](ref))
        .groupBy(row => Tuple1(row.col("id").as("id")))
        .aggregate: row =>
          (
            Aggregate.count.as("n"),
            Aggregate.sum(row.col("value")).as("sum"),
            Aggregate.mean(row.col("value")).as("mean"),
            Aggregate.variancePop(row.col("value")).as("variancePop")
          )
    val run = ColumnarInterpreter
      .prepare(query.plan, ReferenceSources.empty.bind(ref, input))
      .run()
    val result = completed(run)
    try
      assertEquals(run.receipt.fallback, None)
      assert(run.receipt.physicalPlan.contains("HashAggregate[Int32,Primitive]"))
      assertEquals(
        result.rows,
        Right(
          Vector(
            Vector(
              ScalarValue.Int32(2),
              ScalarValue.Int64(2L),
              ScalarValue.Float64(4.0),
              ScalarValue.Float64(2.0),
              ScalarValue.Float64(1.0)
            ),
            Vector(
              ScalarValue.Int32(1),
              ScalarValue.Int64(2L),
              ScalarValue.Float64(4.0),
              ScalarValue.Float64(4.0),
              ScalarValue.Float64(0.0)
            )
          )
        )
      )
    finally
      result.close()
      input.close()

  test("one-shot and prepared hash joins preserve duplicate order and SQL null-key semantics"):
    type Left = (key: Option[Int], leftValue: Long)
    type Right = (rightKey: Option[Int], rightValue: Long)
    val leftRef = reference("columnar-join-left")
    val rightRef = reference("columnar-join-right")
    val leftInput = table[Left](
      Vector(
        (key = Some(1), leftValue = 10L),
        (key = None, leftValue = 20L),
        (key = Some(2), leftValue = 30L)
      ),
      batchSize = 1
    )
    val rightInput = table[Right](
      Vector(
        (rightKey = Some(1), rightValue = 100L),
        (rightKey = Some(1), rightValue = 101L),
        (rightKey = None, rightValue = 999L)
      ),
      batchSize = 2
    )
    val left = value(Frame.values[Left](leftRef))
    val right = value(Frame.values[Right](rightRef))
    val inner = left.innerJoin(right)((lhs, rhs) => (lhs.col("key") === rhs.col("rightKey")).isTrue)
    val outer = left.leftJoin(right)((lhs, rhs) => (lhs.col("key") === rhs.col("rightKey")).isTrue)
    val sources = ReferenceSources.empty.bind(leftRef, leftInput).bind(rightRef, rightInput)
    val innerExecution = ColumnarInterpreter.prepare(inner.plan, sources)
    val outerExecution = ColumnarInterpreter.prepare(outer.plan, sources)
    val preparedExecution = value(ColumnarInterpreter.prepareIndexed(inner.plan, sources))
    val innerRun = innerExecution.run()
    val outerRun = outerExecution.run()
    rightInput.close()
    val preparedRun = preparedExecution.run()
    val repeatedPreparedRun = preparedExecution.run()
    val innerResult = completed(innerRun)
    val outerResult = completed(outerRun)
    val preparedResult = completed(preparedRun)
    val repeatedPreparedResult = completed(repeatedPreparedRun)
    innerExecution.close()
    outerExecution.close()
    preparedExecution.close()
    leftInput.close()
    try
      assertEquals(innerRun.receipt.fallback, None)
      assertEquals(outerRun.receipt.fallback, None)
      assert(innerRun.receipt.physicalPlan.contains("HashJoin[Inner"))
      assert(outerRun.receipt.physicalPlan.contains("HashJoin[LeftOuter"))
      assert(innerRun.receipt.physicalPlan.contains("SelectionGather"))
      assert(outerRun.receipt.physicalPlan.contains("SelectionGather"))
      assert(preparedExecution.physicalExplain.contains("PreparedHashJoin[Inner"))
      assert(preparedRun.receipt.physicalPlan.contains("PreparedHashJoin[Inner"))
      assert(preparedRun.receipt.physicalPlan.contains("SelectionGather"))
      assertEquals(
        innerResult.rows,
        Right(
          Vector(
            Vector(
              ScalarValue.Int32(1),
              ScalarValue.Int64(10L),
              ScalarValue.Int32(1),
              ScalarValue.Int64(100L)
            ),
            Vector(
              ScalarValue.Int32(1),
              ScalarValue.Int64(10L),
              ScalarValue.Int32(1),
              ScalarValue.Int64(101L)
            )
          )
        )
      )
      assertEquals(preparedResult.rows, innerResult.rows)
      assertEquals(repeatedPreparedResult.rows, innerResult.rows)
      assertEquals(
        outerResult.rows.map(_.map(_.drop(2))),
        Right(
          Vector(
            Vector(ScalarValue.Int32(1), ScalarValue.Int64(100L)),
            Vector(ScalarValue.Int32(1), ScalarValue.Int64(101L)),
            Vector(ScalarValue.Null, ScalarValue.Null),
            Vector(ScalarValue.Null, ScalarValue.Null)
          )
        )
      )
      assertEquals(
        preparedExecution.run().result,
        Left(ExecutionError.Storage(StorageError.SourceClosed))
      )
    finally
      innerResult.close()
      outerResult.close()
      preparedResult.close()
      repeatedPreparedResult.close()
      preparedExecution.close()
      innerExecution.close()
      outerExecution.close()
      leftInput.close()
      rightInput.close()

  test("selection-backed outer joins gather every primitive family and survive owner closure"):
    type Left =
      (key: Int, flag: Boolean, ratio: Float, label: String, at: TimestampMicros)
    type Right = (rightKey: Int, score: Double)
    val leftRef = reference("columnar-gather-left")
    val rightRef = reference("columnar-gather-right")
    val leftInput = table[Left](
      Vector(
        (key = 1, flag = true, ratio = 1.5f, label = "one", at = TimestampMicros(7L)),
        (key = 2, flag = false, ratio = -0.0f, label = "two", at = TimestampMicros(8L))
      ),
      batchSize = 1
    )
    val rightInput = table[Right](
      Vector((rightKey = 1, score = Double.NaN)),
      batchSize = 1
    )
    val left = value(Frame.values[Left](leftRef))
    val right = value(Frame.values[Right](rightRef))
    val query =
      left.leftJoin(right): (lhs, rhs) =>
        lhs.col("key") === rhs.col("rightKey")
    val sources =
      ReferenceSources.empty.bind(leftRef, leftInput).bind(rightRef, rightInput)
    val execution = ColumnarInterpreter.prepare(query.plan, sources)
    val prepared = value(ColumnarInterpreter.prepareIndexed(query.plan, sources))
    val run = execution.run()
    val preparedRun = prepared.run()
    val result = completed(run)
    val preparedResult = completed(preparedRun)
    execution.close()
    prepared.close()
    leftInput.close()
    rightInput.close()
    try
      assert(run.receipt.physicalPlan.contains("SelectionGather"))
      assert(preparedRun.receipt.physicalPlan.contains("SelectionGather"))
      val expected =
        Vector(
          Vector(
            ScalarValue.Int32(1),
            ScalarValue.Bool(true),
            ScalarValue.Float32(1.5f),
            ScalarValue.Utf8("one"),
            ScalarValue.Timestamp(7L, TimeUnit.Microsecond),
            ScalarValue.Int32(1),
            ScalarValue.Float64(Double.NaN)
          ),
          Vector(
            ScalarValue.Int32(2),
            ScalarValue.Bool(false),
            ScalarValue.Float32(-0.0f),
            ScalarValue.Utf8("two"),
            ScalarValue.Timestamp(8L, TimeUnit.Microsecond),
            ScalarValue.Null,
            ScalarValue.Null
          )
        )
      val actual = value(result.rows)
      val preparedActual = value(preparedResult.rows)
      def normalized(rows: Vector[Vector[ScalarValue]]): Vector[Vector[String]] =
        rows.map:
          _.map:
            case ScalarValue.Float64(value) if value.isNaN => "Float64(NaN)"
            case value                                     => value.toString
      assertEquals(normalized(actual), normalized(expected))
      assertEquals(normalized(preparedActual), normalized(expected))
      actual.head(6) match
        case ScalarValue.Float64(value) =>
          assertEquals(
            java.lang.Double.doubleToRawLongBits(value),
            java.lang.Double.doubleToRawLongBits(Double.NaN)
          )
        case value => fail(s"expected gathered NaN, received $value")
      assertEquals(result.checksum, preparedResult.checksum)
    finally
      result.close()
      preparedResult.close()
      execution.close()
      prepared.close()
      leftInput.close()
      rightInput.close()

  test("single required Int32 distinct uses a stable primitive accumulator"):
    type Input = (id: Int)
    val ref = reference("columnar-distinct-int32")
    val input = table[Input](
      Vector(
        (id = 3),
        (id = 1),
        (id = 3),
        (id = -1),
        (id = 1),
        (id = 2)
      ),
      batchSize = 2
    )
    val query = value(Frame.values[Input](ref)).distinct
    val run = ColumnarInterpreter
      .prepare(query.plan, ReferenceSources.empty.bind(ref, input))
      .run()
    val result = completed(run)
    input.close()
    try
      assertEquals(run.receipt.fallback, None)
      assert(run.receipt.physicalPlan.contains("HashDistinct[Int32,Primitive]"))
      assertEquals(
        result.rows,
        Right(
          Vector(
            Vector(ScalarValue.Int32(3)),
            Vector(ScalarValue.Int32(1)),
            Vector(ScalarValue.Int32(-1)),
            Vector(ScalarValue.Int32(2))
          )
        )
      )
    finally result.close()

  test("hash distinct compares decoded dictionary values and canonical floating keys"):
    type Input = (word: String, value: Double)
    val descriptor = summon[SchemaDescriptor[Input]]
    def dictionaryBatch(
        indices: Array[Int],
        dictionary: Array[String],
        doubles: Array[Double]
    ): RecordBatch =
      val indexArray = value(ColumnArray.int32(indices))
      val dictionaryValues = value(ColumnArray.utf8(dictionary))
      val encoded = value(ColumnArray.dictionary(indexArray, dictionaryValues))
      val numeric = value(ColumnArray.float64(doubles))
      RecordBatch(descriptor.schema, Vector(encoded, numeric)) match
        case Right(batch) => batch
        case Left(error)  =>
          encoded.close()
          numeric.close()
          fail(error.message)

    val nanA = java.lang.Double.longBitsToDouble(0x7ff8000000000001L)
    val nanB = java.lang.Double.longBitsToDouble(0x7ff8000000000011L)
    val input = value(
      Table[Input](
        Vector(
          dictionaryBatch(Array(0, 1), Array("a", "b"), Array(nanA, 0.0)),
          dictionaryBatch(Array(1, 0), Array("b", "a"), Array(nanB, -0.0))
        )
      )
    )
    val ref = reference("columnar-distinct")
    val query = value(Frame.values[Input](ref)).distinct
    val run = ColumnarInterpreter
      .prepare(query.plan, ReferenceSources.empty.bind(ref, input))
      .run()
    val result = completed(run)
    try
      assertEquals(run.receipt.fallback, None)
      assert(run.receipt.physicalPlan.contains("HashDistinct"))
      assertEquals(
        result.rows.map(_.map(ColumnarInterpreterSuite.fingerprint).toSet),
        Right(Set("a|nan", "b|zero"))
      )
    finally
      result.close()
      input.close()

  test("a closed result rejects every terminal"):
    type Input = (id: Int)
    val ref = reference("columnar-close")
    val input = table[Input](Vector((id = 1)))
    val query = value(Frame.values[Input](ref)).select(row => Tuple1(row.col("id").as("id")))
    val result = completed(
      ColumnarInterpreter
        .prepare(query.plan, ReferenceSources.empty.bind(ref, input))
        .run()
    )
    result.close()
    try
      assert(result.isClosed)
      assertEquals(
        result.rows,
        Left(ExecutionError.Storage(StorageError.SourceClosed))
      )
      assertEquals(
        result.checksum,
        Left(ExecutionError.Storage(StorageError.SourceClosed))
      )
    finally input.close()

  test("direct and dictionary UTF-8 checksums preserve Java hash semantics"):
    val strings = Array("", "plain", "é", "漢字", "😀", "e\u0301", "𝄞")
    val expected = strings.foldLeft(1L): (hash, value) =>
      hash * 31L + value.hashCode.toLong
    val direct = value(ColumnArray.utf8(strings))
    val indices = value(ColumnArray.int32(strings.indices.toArray))
    val dictionaryValues = value(ColumnArray.utf8(strings))
    val dictionary = value(ColumnArray.dictionary(indices, dictionaryValues))
    try
      assertEquals(ColumnarInterpreter.columnChecksum(direct), Right(expected))
      assertEquals(ColumnarInterpreter.columnChecksum(dictionary), Right(expected))
    finally
      direct.close()
      dictionary.close()


  test("collectBatches materializes Arrow batches matching the reference interpreter"):
    type Input = (id: Int, value: Option[Double], label: String)
    type Output = (id: Int, value: Option[Double], label: String)
    val ref = reference("columnar-collect")
    val rows = Vector(
      (id = 1, value = Some(1.5), label = "a"),
      (id = 2, value = None, label = "b"),
      (id = 3, value = Some(-0.0), label = "c"),
      (id = 4, value = Some(2.25), label = "d")
    )
    val input = table[Input](rows)
    val source = value(Frame.values[Input](ref))
    val query: Frame[Output] = source.filter(row => row.col("id") > 1)
    val sources = ReferenceSources.empty.bind(ref, input)

    // The engine must actually take this plan; a silent decline would make the comparison
    // below vacuous, because both sides would then be the reference interpreter.
    val batches = ColumnarInterpreter.collectBatches(query.plan, sources) match
      case Left(reason) => fail(s"columnar engine declined a supported plan: $reason")
      case Right(value) => value

    val produced = batches.flatMap: batch =>
      (0 until batch.rowCount).toVector.map: row =>
        batch.columns.map(column => value(column.scalar(row)))
    val expected =
      value(ReferenceInterpreter.prepare(query.plan, sources).collect[Output]).batches
        .flatMap: batch =>
          (0 until batch.rowCount).toVector.map: row =>
            batch.columns.map(column => value(column.scalar(row)))

    assertEquals(produced, expected)
    assertEquals(produced.length, 3)



object ColumnarInterpreterSuite:
  private def fingerprint(row: Vector[ScalarValue]): String = row match
    case Vector(ScalarValue.Utf8(word), ScalarValue.Float64(value)) =>
      val floating =
        if value.isNaN then "nan"
        else if value == 0.0 then "zero"
        else java.lang.Double.doubleToLongBits(value).toString
      s"$word|$floating"
    case other => other.toString
