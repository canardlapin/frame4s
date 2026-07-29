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

object ColumnarInterpreterSuite:
  private def fingerprint(row: Vector[ScalarValue]): String = row match
    case Vector(ScalarValue.Utf8(word), ScalarValue.Float64(value)) =>
      val floating =
        if value.isNaN then "nan"
        else if value == 0.0 then "zero"
        else java.lang.Double.doubleToLongBits(value).toString
      s"$word|$floating"
    case other => other.toString
