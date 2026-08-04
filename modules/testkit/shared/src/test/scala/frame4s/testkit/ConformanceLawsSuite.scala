package frame4s.testkit

import frame4s.*
import munit.ScalaCheckSuite
import org.scalacheck.Prop.forAll
import org.scalacheck.Test

class ConformanceLawsSuite extends ScalaCheckSuite:
  /** Deliberately keep the seed random so successive courts explore new cases. MUnit prints the
    * failing seed for exact replay; one worker keeps shrinking and resource ownership deterministic
    * on both the JVM and Scala.js.
    */
  override def scalaCheckTestParameters: Test.Parameters =
    super.scalaCheckTestParameters
      .withMinSuccessfulTests(200)
      .withWorkers(1)

  type LeftKeys = (key: Option[Int])
  type RightKeys = (rightKey: Option[Int])
  type StatisticInput = (value: Option[Double])
  type StatisticOutput = (
      root: Option[Double],
      variancePop: Option[Double],
      stddevPop: Option[Double]
  )

  private def value[E, A](result: Either[E, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def execute[S <: scala.NamedTuple.AnyNamedTuple](
      frame: Frame[S],
      table: Table[S],
      reference: SourceRef
  )(using SchemaDescriptor[S]): Either[ExecutionError, ObservedTable] =
    ReferenceBackend.execute(
      frame,
      ReferenceSources.empty.bind(reference, table)
    ) match
      case BackendAttempt.Completed(result, _)            => result
      case BackendAttempt.Residual(capability, reason, _) =>
        fail(s"reference backend reported residual $capability: $reason")

  test("observed equivalence canonicalizes NaN payloads without erasing signed zero"):
    type Output = (f32: Float, f64: Double)
    val schema = summon[SchemaDescriptor[Output]].schema
    val left = ObservedTable(
      schema,
      Vector(
        Vector(
          ScalarValue.Float32(java.lang.Float.intBitsToFloat(0x7fc00001)),
          ScalarValue.Float64(java.lang.Double.longBitsToDouble(0x7ff8000000000001L))
        )
      ),
      OrderGuarantee.Stable
    )
    val otherNaNs = ObservedTable(
      schema,
      Vector(
        Vector(
          ScalarValue.Float32(java.lang.Float.intBitsToFloat(0x7fc00011)),
          ScalarValue.Float64(java.lang.Double.longBitsToDouble(0x7ff8000000000011L))
        )
      ),
      OrderGuarantee.Stable
    )
    val oppositeZeros = ObservedTable(
      schema,
      Vector(Vector(ScalarValue.Float32(0.0f), ScalarValue.Float64(0.0))),
      OrderGuarantee.Stable
    )
    val negativeZeros = ObservedTable(
      schema,
      Vector(Vector(ScalarValue.Float32(-0.0f), ScalarValue.Float64(-0.0))),
      OrderGuarantee.Stable
    )

    assert(left.equivalentTo(otherNaNs))
    assert(!oppositeZeros.equivalentTo(negativeZeros))

  test("empty and all-null reductions have truthful schemas and backend results"):
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

    val emptyReference = value(SourceRef.values("empty-reductions", "empty-reductions"))
    val emptySource = value(Frame.values[Required](emptyReference))
    val emptyQuery: Frame[Result] = emptySource
      .groupBy(_ => EmptyTuple)
      .aggregate: row =>
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

    val nullReference = value(SourceRef.values("null-reductions", "null-reductions"))
    val nullSource = value(Frame.values[NullableInput](nullReference))
    val nullQuery: Frame[Result] = nullSource
      .groupBy(_ => EmptyTuple)
      .aggregate: row =>
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

    val empty = value(Table.takeOwnership[Required](Vector.empty))
    val nullRows: Vector[NullableInput] =
      Vector((i = None, l = None, f = None, d = None, text = None))
    val allNull = value(Table.fromRows[NullableInput](nullRows, batchSize = 1))

    def check[S <: scala.NamedTuple.AnyNamedTuple](
        query: Frame[S],
        sources: ReferenceSources,
        count: Long
    )(using SchemaDescriptor[S]): Unit =
      BackendConformance.compare(query, sources, ColumnarBackend) match
        case BackendComparison.Equivalent(_, _) => ()
        case other                              => fail(s"aggregate backends disagreed: $other")
      val (observed, _) = completed(ReferenceBackend.execute(query, sources))
      assertEquals(observed.schema.fields.map(_.nullable), false +: Vector.fill(26)(true))
      assertEquals(
        observed.rows,
        Vector(ScalarValue.Int64(count) +: Vector.fill(26)(ScalarValue.Null))
      )

    try
      check(emptyQuery, ReferenceSources.empty.bind(emptyReference, empty), count = 0L)
      check(nullQuery, ReferenceSources.empty.bind(nullReference, allNull), count = 1L)
    finally
      empty.close()
      allNull.close()

  property("unique and deliberately invalid schemas are generated"):
    forAll(Frame4sGenerators.uniqueFields, Frame4sGenerators.invalidSchema): (valid, invalid) =>
      assert(Schema(valid).isRight)
      invalid.expected match
        case InvalidSchemaCase.Duplicate =>
          assert(Schema(invalid.fields).left.exists(_.isInstanceOf[SchemaError.DuplicateFieldName]))
        case InvalidSchemaCase.Empty =>
          assert(Schema(invalid.fields).left.exists(_.isInstanceOf[SchemaError.EmptyFieldName]))
        case InvalidSchemaCase.NullName =>
          assert(Schema(invalid.fields).left.exists(_.isInstanceOf[SchemaError.NullFieldName]))

  property("legal source batch boundaries preserve rows, schemas, and order"):
    forAll(Frame4sGenerators.rows, Frame4sGenerators.batchSize): (rows, batchSize) =>
      val reference = value(SourceRef.values("batch-law", "batch-law"))
      val frame = value(Frame.values[ConformanceFixtures.RowSchema](reference))
      val oneBatch = value(ConformanceFixtures.table(rows, math.max(1, rows.size)))
      val manyBatches = value(ConformanceFixtures.table(rows, batchSize))
      try
        val first = execute(frame, oneBatch, reference)
        val second = execute(frame, manyBatches, reference)
        (first, second) match
          case (Right(left), Right(right)) => assert(left.equivalentTo(right))
          case _                           => assertEquals(first, second)
      finally
        oneBatch.close()
        manyBatches.close()

  property("normalization preserves values, exact failures, schema, and order"):
    forAll(Frame4sGenerators.rows, Frame4sGenerators.intValue): (rows, threshold) =>
      val reference = value(SourceRef.values("normalize-law", "normalize-law"))
      val source = value(Frame.values[ConformanceFixtures.RowSchema](reference))
      val raw = value(
        source
          .select: row =>
            (
              row.col("id").as("id"),
              row.col("value").as("value")
            )
          .filter(row => row.col("id") >= Expr.literal(threshold))
          .limit(37)
      )
      val normalized = raw.normalized._1
      val table = value(ConformanceFixtures.table(rows, 7))
      val sources = ReferenceSources.empty.bind(reference, table)
      try
        val original = ReferenceBackend.execute(raw, sources)
        val rewritten = ReferenceBackend.execute(normalized, sources)
        (original, rewritten) match
          case (
                BackendAttempt.Completed(Left(left), _),
                BackendAttempt.Completed(Left(right), _)
              ) =>
            assertEquals(left, right)
          case (
                BackendAttempt.Completed(Right(left), _),
                BackendAttempt.Completed(Right(right), _)
              ) =>
            assert(left.equivalentTo(right))
          case other => fail(s"normalization changed the success boundary: $other")
      finally table.close()

  property("generated total and checked expressions preserve the exact success boundary"):
    forAll(Frame4sGenerators.rows, Frame4sGenerators.expression): (generated, expression) =>
      val reference = value(SourceRef.values("expression-law", "expression-law"))
      val source = value(Frame.values[ConformanceFixtures.RowSchema](reference))
      expression match
        case GeneratedExpression.TotalThreshold(threshold) =>
          val raw = source.filter(row => row.col("id") >= Expr.literal(threshold))
          val normalized = raw.normalized._1
          val table = value(ConformanceFixtures.table(generated, 5))
          try
            val sources = ReferenceSources.empty.bind(reference, table)
            assertAttemptsEquivalent(
              ReferenceBackend.execute(raw, sources),
              ReferenceBackend.execute(normalized, sources)
            )
          finally table.close()
        case GeneratedExpression.CheckedAdd(delta) =>
          val boundary =
            if delta > 0 then Int.MaxValue
            else Int.MinValue
          val rows = generated :+ ConformanceRow(boundary, None, None, 0L)
          val raw = source.select: row =>
            Tuple1((row.col("id") + Expr.literal(delta)).as("result"))
          val normalized = raw.normalized._1
          val table = value(ConformanceFixtures.table(rows, 5))
          try
            val sources = ReferenceSources.empty.bind(reference, table)
            assertAttemptsEquivalent(
              ReferenceBackend.execute(raw, sources),
              ReferenceBackend.execute(normalized, sources)
            )
          finally table.close()

  property("the backend-conformance boundary admits an oracle-equivalent backend"):
    forAll(Frame4sGenerators.rows, Frame4sGenerators.intValue): (rows, threshold) =>
      val reference = value(SourceRef.values("backend-law", "backend-law"))
      val frame = value(Frame.values[ConformanceFixtures.RowSchema](reference))
        .filter(row => row.col("id") >= Expr.literal(threshold))
      val table = value(ConformanceFixtures.table(rows, 11))
      try
        BackendConformance.compare(
          frame,
          ReferenceSources.empty.bind(reference, table),
          ReferenceBackend
        ) match
          case BackendComparison.Equivalent(oracle, candidate) =>
            assertEquals(oracle.fallback, None)
            assertEquals(candidate.fallback, None)
          case other => fail(s"reference did not agree with itself: $other")
      finally table.close()

  property("columnar primitive and nullable projection scans agree with the oracle"):
    type Projection = (id: Int, value: Option[Double])
    forAll(Frame4sGenerators.rows, Frame4sGenerators.batchSize): (rows, batchSize) =>
      val reference = value(SourceRef.values("columnar-projection-law", "columnar-projection-law"))
      val source = value(Frame.values[ConformanceFixtures.RowSchema](reference))
      val query: Frame[Projection] =
        source.select: row =>
          (
            row.col("id").as("id"),
            row.col("value").as("value")
          )
      val table = value(ConformanceFixtures.table(rows, batchSize))
      try
        BackendConformance.compare(
          query,
          ReferenceSources.empty.bind(reference, table),
          ColumnarBackend
        ) match
          case BackendComparison.Equivalent(_, candidate) =>
            assertEquals(candidate.fallback, None)
            assert(candidate.physicalPlan.contains("ScanProject"))
          case other => fail(s"columnar projection disagreed with the oracle: $other")
      finally table.close()

  property("general filter and withColumn expressions preserve exact values and failures"):
    type Output = (
        id: Int,
        key: Option[String],
        value: Option[Double],
        at: TimestampMicros,
        score: Int
    )
    forAll(
      Frame4sGenerators.rows,
      Frame4sGenerators.batchSize,
      Frame4sGenerators.intValue
    ): (rows, batchSize, threshold) =>
      val reference = value(SourceRef.values("expression-pipeline-law", "expression-pipeline-law"))
      val source = value(Frame.values[ConformanceFixtures.RowSchema](reference))
      val filtered = source.filter: row =>
        (row.col("id") >= Expr.literal(threshold)) &&
          (row.col("id") <= Expr.literal(Int.MaxValue))
      val query: Frame[Output] = filtered.withColumn("score"): row =>
        row.col("id") * Expr.literal(3) - Expr.literal(1)
      val table = value(ConformanceFixtures.table(rows, math.max(1, batchSize)))
      try
        BackendConformance.compare(
          query,
          ReferenceSources.empty.bind(reference, table),
          ColumnarBackend
        ) match
          case BackendComparison.Equivalent(_, candidate) =>
            assertEquals(candidate.fallback, None)
            assert(candidate.physicalPlan.contains("ExpressionPipeline"))
          case other => fail(s"general expression pipeline disagreed with the oracle: $other")
      finally table.close()

  property("nullable floating withColumn arithmetic preserves exact values and nulls"):
    type Output = (
        id: Int,
        key: Option[String],
        value: Option[Double],
        at: TimestampMicros,
        adjusted: Option[Double]
    )
    forAll(Frame4sGenerators.rows, Frame4sGenerators.batchSize): (rows, batchSize) =>
      val reference =
        value(SourceRef.values("nullable-arithmetic-law", "nullable-arithmetic-law"))
      val source = value(Frame.values[ConformanceFixtures.RowSchema](reference))
      val query: Frame[Output] = source.withColumn("adjusted"): row =>
        (row.col("value") / Some(2.0)) + Some(1.0)
      val table = value(ConformanceFixtures.table(rows, math.max(1, batchSize)))
      try
        BackendConformance.compare(
          query,
          ReferenceSources.empty.bind(reference, table),
          ColumnarBackend
        ) match
          case BackendComparison.Equivalent(_, candidate) =>
            assertEquals(candidate.fallback, None)
            assert(candidate.physicalPlan.contains("ExpressionPipeline"))
          case other => fail(s"nullable arithmetic pipeline disagreed with the oracle: $other")
      finally table.close()

  property("nullable UTF-8 filters preserve SQL truth and Unicode equality"):
    type Projection = (id: Int, key: Option[String])
    forAll(Frame4sGenerators.rows, Frame4sGenerators.batchSize): (rows, batchSize) =>
      val reference = value(SourceRef.values("utf8-filter-law", "utf8-filter-law"))
      val source = value(Frame.values[ConformanceFixtures.RowSchema](reference))
      val filtered = source.filter: row =>
        (row.col("key") === Expr.literal(Option("λ"))).isTrue
      val query: Frame[Projection] = filtered
        .select(row => (row.col("id"), row.col("key")))
      val table = value(ConformanceFixtures.table(rows, math.max(1, batchSize)))
      try
        BackendConformance.compare(
          query,
          ReferenceSources.empty.bind(reference, table),
          ColumnarBackend
        ) match
          case BackendComparison.Equivalent(_, candidate) =>
            assertEquals(candidate.fallback, None)
            assert(candidate.physicalPlan.contains("ExpressionPipeline"))
          case other => fail(s"UTF-8 expression pipeline disagreed with the oracle: $other")
      finally table.close()

  property("UTF-8 ordering filters preserve unsigned binary collation"):
    type Projection = (id: Int, key: Option[String])
    forAll(
      Frame4sGenerators.rows,
      Frame4sGenerators.batchSize,
      Frame4sGenerators.utf8Value
    ): (rows, batchSize, threshold) =>
      val reference = value(SourceRef.values("utf8-order-law", "utf8-order-law"))
      val source = value(Frame.values[ConformanceFixtures.RowSchema](reference))
      val filtered = source.filter: row =>
        (row.col("key") >= Expr.literal(Option(threshold))).isTrue
      val query: Frame[Projection] = filtered
        .select(row => (row.col("id"), row.col("key")))
      val table = value(ConformanceFixtures.table(rows, math.max(1, batchSize)))
      try
        BackendConformance.compare(
          query,
          ReferenceSources.empty.bind(reference, table),
          ColumnarBackend
        ) match
          case BackendComparison.Equivalent(_, candidate) =>
            assertEquals(candidate.fallback, None)
            assert(candidate.physicalPlan.contains("ExpressionPipeline"))
          case other => fail(s"UTF-8 ordering pipeline disagreed with the oracle: $other")
      finally table.close()

  property("general grouped reductions admit projected nullable keys and multiple measures"):
    type Input = (
        key: Option[String],
        bucket: Int,
        first: Option[Double],
        second: Option[Double]
    )
    type Selected = (
        second: Option[Double],
        key: Option[String],
        first: Option[Double],
        bucket: Int
    )
    type Output = (
        key: Option[String],
        bucket: Int,
        n: Long,
        firstMean: Option[Double],
        secondMean: Option[Double]
    )
    forAll(Frame4sGenerators.rows, Frame4sGenerators.batchSize): (rows, batchSize) =>
      val reference = value(SourceRef.values("general-aggregate-law", "general-aggregate-law"))
      val inputRows = rows.map: row =>
        (
          key = row.key,
          bucket = row.id % 5,
          first = row.value,
          second = Option.when((row.id & 1) == 0)(row.id.toDouble / 8.0)
        )
      val source = value(Frame.values[Input](reference))
      val selected: Frame[Selected] = source.select: row =>
        (
          row.col("second"),
          row.col("key"),
          row.col("first"),
          row.col("bucket")
        )
      val query: Frame[Output] = selected
        .groupBy(row => (row.col("key"), row.col("bucket")))
        .aggregate: row =>
          (
            Aggregate.count.as("n"),
            Aggregate.mean(row.col("first")).as("firstMean"),
            Aggregate.mean(row.col("second")).as("secondMean")
          )
      val table = value(Table.fromRows[Input](inputRows, math.max(1, batchSize)))
      try
        BackendConformance.compare(
          query,
          ReferenceSources.empty.bind(reference, table),
          ColumnarBackend
        ) match
          case BackendComparison.Equivalent(_, candidate) =>
            assertEquals(candidate.fallback, None)
            assert(candidate.physicalPlan.contains("HashAggregate[General"))
          case other => fail(s"general aggregate disagreed with the oracle: $other")
      finally table.close()

  property("primitive Int32 distinct agrees with the oracle across generated batch boundaries"):
    type Projection = (id: Int)
    forAll(Frame4sGenerators.rows, Frame4sGenerators.batchSize): (rows, batchSize) =>
      val legalBatchSize = math.max(1, batchSize)
      val reference = value(SourceRef.values("int-distinct-law", "int-distinct-law"))
      val source = value(Frame.values[ConformanceFixtures.RowSchema](reference))
      val query: Frame[Projection] =
        source.select(row => Tuple1(row.col("id").as("id"))).distinct
      val table = value(ConformanceFixtures.table(rows, legalBatchSize))
      try
        BackendConformance.compare(
          query,
          ReferenceSources.empty.bind(reference, table),
          ColumnarBackend
        ) match
          case BackendComparison.Equivalent(_, candidate) =>
            assertEquals(candidate.fallback, None)
            assert(candidate.physicalPlan.contains("HashDistinct[Int32,Primitive]"))
          case other => fail(s"primitive Int32 distinct disagreed with the oracle: $other")
      finally table.close()

  property("primitive keyed aggregates preserve exact oracle moments and floating edge cases"):
    type IntOutput = (
        id: Int,
        n: Long,
        sum: Option[Double],
        mean: Option[Double],
        variancePop: Option[Double]
    )
    type Utf8Input = (key: String, value: Option[Double])
    type Utf8Output = (
        key: String,
        n: Long,
        sum: Option[Double],
        mean: Option[Double],
        variancePop: Option[Double]
    )
    forAll(Frame4sGenerators.rows, Frame4sGenerators.batchSize): (rows, batchSize) =>
      val legalBatchSize = math.max(1, batchSize)
      val intReference = value(SourceRef.values("int-aggregate-law", "int-aggregate-law"))
      val intSource = value(Frame.values[ConformanceFixtures.RowSchema](intReference))
      val intQuery: Frame[IntOutput] =
        intSource
          .groupBy(row => Tuple1(row.col("id").as("id")))
          .aggregate: row =>
            (
              Aggregate.count.as("n"),
              Aggregate.sum(row.col("value")).as("sum"),
              Aggregate.mean(row.col("value")).as("mean"),
              Aggregate.variancePop(row.col("value")).as("variancePop")
            )
      val intTable = value(ConformanceFixtures.table(rows, legalBatchSize))

      val utf8Reference = value(SourceRef.values("utf8-aggregate-law", "utf8-aggregate-law"))
      val utf8Source = value(Frame.values[Utf8Input](utf8Reference))
      val utf8Query: Frame[Utf8Output] =
        utf8Source
          .groupBy(row => Tuple1(row.col("key").as("key")))
          .aggregate: row =>
            (
              Aggregate.count.as("n"),
              Aggregate.sum(row.col("value")).as("sum"),
              Aggregate.mean(row.col("value")).as("mean"),
              Aggregate.variancePop(row.col("value")).as("variancePop")
            )
      val utf8Rows = rows.map: row =>
        (key = row.key.getOrElse("<generated-null>"), value = row.value)
      val utf8Table = value(Table.fromRows[Utf8Input](utf8Rows, legalBatchSize))

      try
        BackendConformance.compare(
          intQuery,
          ReferenceSources.empty.bind(intReference, intTable),
          ColumnarBackend
        ) match
          case BackendComparison.Equivalent(_, candidate) =>
            assertEquals(candidate.fallback, None)
            assert(candidate.physicalPlan.contains("HashAggregate[Int32,Primitive]"))
          case other => fail(s"primitive Int32 aggregate disagreed with the oracle: $other")

        BackendConformance.compare(
          utf8Query,
          ReferenceSources.empty.bind(utf8Reference, utf8Table),
          ColumnarBackend
        ) match
          case BackendComparison.Equivalent(_, candidate) =>
            assertEquals(candidate.fallback, None)
            assert(candidate.physicalPlan.contains("HashAggregate[Utf8,Primitive]"))
          case other => fail(s"primitive UTF-8 aggregate disagreed with the oracle: $other")
      finally
        intTable.close()
        utf8Table.close()

  property("columnar fused checked pipelines agree on values and exact failures"):
    type Projection = (id: Int, next: Int)
    forAll(
      Frame4sGenerators.rows,
      Frame4sGenerators.intValue,
      Frame4sGenerators.intValue,
      Frame4sGenerators.batchSize
    ): (rows, threshold, delta, batchSize) =>
      val reference = value(SourceRef.values("columnar-fused-law", "columnar-fused-law"))
      val source = value(Frame.values[ConformanceFixtures.RowSchema](reference))
      val query: Frame[Projection] =
        source
          .filter(row => row.col("id") >= Expr.literal(threshold))
          .select: row =>
            (
              row.col("id").as("id"),
              (row.col("id") + Expr.literal(delta)).as("next")
            )
      val table = value(ConformanceFixtures.table(rows, batchSize))
      try
        BackendConformance.compare(
          query,
          ReferenceSources.empty.bind(reference, table),
          ColumnarBackend
        ) match
          case BackendComparison.Equivalent(_, candidate) =>
            assertEquals(candidate.fallback, None)
            assert(candidate.physicalPlan.contains("FusedFilterProjectCheckedInt32"))
          case other => fail(s"columnar fused pipeline disagreed with the oracle: $other")
      finally table.close()

  property("columnar unsupported shapes use an explicit whole-plan oracle fallback"):
    forAll(Frame4sGenerators.rows, Frame4sGenerators.intValue): (rows, threshold) =>
      val reference = value(SourceRef.values("columnar-fallback-law", "columnar-fallback-law"))
      val source = value(Frame.values[ConformanceFixtures.RowSchema](reference))
      val query = value(
        source
          .filter(row => row.col("id") >= Expr.literal(threshold))
          .limit(37)
      )
      val table = value(ConformanceFixtures.table(rows, 7))
      try
        BackendConformance.compare(
          query,
          ReferenceSources.empty.bind(reference, table),
          ColumnarBackend
        ) match
          case BackendComparison.Equivalent(_, candidate) =>
            assertEquals(candidate.fallback, Some("unsupported logical shape Limit"))
            assert(candidate.physicalPlan.contains("ReferenceWholePlan"))
          case other => fail(s"whole-plan fallback disagreed with the oracle: $other")
      finally table.close()

  property("generated null, duplicate, and skewed join keys obey SQL equality cardinality"):
    forAll(Frame4sGenerators.joinShape): shape =>
      val leftReference = value(SourceRef.values("join-law-left", "join-law-left"))
      val rightReference = value(SourceRef.values("join-law-right", "join-law-right"))
      val left = value(Frame.values[LeftKeys](leftReference))
      val right = value(Frame.values[RightKeys](rightReference))
      val joined = left.innerJoin(right): (lhs, rhs) =>
        (lhs.col("key") === rhs.col("rightKey")).isTrue
      val leftTable = optionalLeftKeys(shape.leftKeys)
      val rightTable = optionalRightKeys(shape.rightKeys)
      val expected = shape.leftKeys.foldLeft(0L): (count, key) =>
        count + key.fold(0L)(actual => shape.rightKeys.count(_.contains(actual)).toLong)
      try
        ReferenceBackend.execute(
          joined,
          ReferenceSources.empty
            .bind(leftReference, leftTable)
            .bind(rightReference, rightTable)
        ) match
          case BackendAttempt.Completed(Right(observed), _) =>
            assertEquals(observed.rows.size.toLong, expected)
          case other => fail(s"join execution failed: $other")
      finally
        leftTable.close()
        rightTable.close()

  property("columnar hash inner and left joins agree with the oracle"):
    forAll(Frame4sGenerators.joinShape): shape =>
      val leftReference = value(SourceRef.values("columnar-join-left", "columnar-join-left"))
      val rightReference = value(SourceRef.values("columnar-join-right", "columnar-join-right"))
      val left = value(Frame.values[LeftKeys](leftReference))
      val right = value(Frame.values[RightKeys](rightReference))
      val inner = left.innerJoin(right): (lhs, rhs) =>
        (lhs.col("key") === rhs.col("rightKey")).isTrue
      val outer = left.leftJoin(right): (lhs, rhs) =>
        (lhs.col("key") === rhs.col("rightKey")).isTrue
      val leftTable = optionalLeftKeys(shape.leftKeys)
      val rightTable = optionalRightKeys(shape.rightKeys)
      val sources = ReferenceSources.empty
        .bind(leftReference, leftTable)
        .bind(rightReference, rightTable)
      try
        Vector(inner, outer).foreach: query =>
          BackendConformance.compare(query, sources, ColumnarBackend) match
            case BackendComparison.Equivalent(_, candidate) =>
              assertEquals(candidate.fallback, None)
              assert(candidate.physicalPlan.contains("HashJoin"))
            case other => fail(s"columnar hash join disagreed with the oracle: $other")
      finally
        leftTable.close()
        rightTable.close()

  property("distinct agrees with an independent logical-key set oracle"):
    forAll(Frame4sGenerators.rows, Frame4sGenerators.batchSize): (rows, batchSize) =>
      val reference = value(SourceRef.values("distinct-law", "distinct-law"))
      val frame = value(Frame.values[ConformanceFixtures.RowSchema](reference)).distinct
      val table = value(ConformanceFixtures.table(rows, batchSize))
      try
        ReferenceBackend.execute(
          frame,
          ReferenceSources.empty.bind(reference, table)
        ) match
          case BackendAttempt.Completed(Right(observed), receipt) =>
            assert(receipt.physicalPlan.contains("Aggregate"))
            assertEquals(
              observed.rows.map(canonicalObservedRow).toSet,
              rows.map(canonicalInputRow).toSet
            )
            assertEquals(observed.order, OrderGuarantee.Unspecified)
          case other => fail(s"distinct execution failed: $other")
        BackendConformance.compare(
          frame,
          ReferenceSources.empty.bind(reference, table),
          ColumnarBackend
        ) match
          case BackendComparison.Equivalent(_, candidate) =>
            assertEquals(candidate.fallback, None)
            assert(candidate.physicalPlan.contains("HashDistinct"))
          case other => fail(s"columnar distinct disagreed with the oracle: $other")
      finally table.close()

  property("unionAll streams stable branches in order and preserves duplicates"):
    forAll(Frame4sGenerators.rows, Frame4sGenerators.batchSize): (rows, splitSeed) =>
      val split = if rows.isEmpty then 0 else math.floorMod(splitSeed, rows.size + 1)
      val leftRows = rows.take(split)
      val rightRows = rows.drop(split)
      val leftReference = value(SourceRef.values("union-law-left", "union-law-left"))
      val rightReference = value(SourceRef.values("union-law-right", "union-law-right"))
      val left = value(Frame.values[ConformanceFixtures.RowSchema](leftReference))
      val right = value(Frame.values[ConformanceFixtures.RowSchema](rightReference))
      val query = left.unionAll(right)
      val leftTable = value(ConformanceFixtures.table(leftRows, 3))
      val rightTable = value(ConformanceFixtures.table(rightRows, 5))
      val sources = ReferenceSources.empty
        .bind(leftReference, leftTable)
        .bind(rightReference, rightTable)
      try
        ReferenceBackend.execute(query, sources) match
          case BackendAttempt.Completed(Right(observed), receipt) =>
            assert(receipt.physicalPlan.contains("UnionAll"))
            assertEquals(
              observed.rows.map(canonicalObservedRow),
              rows.map(canonicalInputRow)
            )
            assertEquals(observed.order, OrderGuarantee.Stable)
          case other => fail(s"union execution failed: $other")
        BackendConformance.compare(query, sources, ColumnarBackend) match
          case BackendComparison.Equivalent(_, candidate) =>
            assertEquals(candidate.fallback, None)
            assert(candidate.physicalPlan.contains("StreamingUnionAll"))
          case other => fail(s"columnar union disagreed with the oracle: $other")
      finally
        leftTable.close()
        rightTable.close()

  property("semi and anti joins implement existential truth without multiplying rows"):
    forAll(Frame4sGenerators.joinShape): shape =>
      val leftReference = value(SourceRef.values("existence-left", "existence-left"))
      val rightReference = value(SourceRef.values("existence-right", "existence-right"))
      val left = value(Frame.values[LeftKeys](leftReference))
      val right = value(Frame.values[RightKeys](rightReference))
      val semi = left.semiJoin(right): (lhs, rhs) =>
        (lhs.col("key") === rhs.col("rightKey")).isTrue
      val anti = left.antiJoin(right): (lhs, rhs) =>
        (lhs.col("key") === rhs.col("rightKey")).isTrue
      val leftTable = optionalLeftKeys(shape.leftKeys)
      val rightTable = optionalRightKeys(shape.rightKeys)
      def expected(matched: Boolean): Vector[Option[Int]] =
        shape.leftKeys.filter: key =>
          val exists = key.exists(actual => shape.rightKeys.exists(_.contains(actual)))
          exists == matched
      try
        val sources = ReferenceSources.empty
          .bind(leftReference, leftTable)
          .bind(rightReference, rightTable)
        val observedSemi = completed(ReferenceBackend.execute(semi, sources))
        val observedAnti = completed(ReferenceBackend.execute(anti, sources))
        assert(observedSemi._2.physicalPlan.contains("LeftSemi"))
        assert(observedAnti._2.physicalPlan.contains("LeftAnti"))
        assertEquals(observedSemi._1.rows.map(optionalInt), expected(matched = true))
        assertEquals(observedAnti._1.rows.map(optionalInt), expected(matched = false))
        Vector(semi, anti).foreach: query =>
          BackendConformance.compare(query, sources, ColumnarBackend) match
            case BackendComparison.Equivalent(_, candidate) =>
              assertEquals(candidate.fallback, None)
              assert(candidate.physicalPlan.contains("HashJoin"))
            case other => fail(s"columnar existence join disagreed with the oracle: $other")
      finally
        leftTable.close()
        rightTable.close()

  property("sqrt and population statistics are invariant to legal batch boundaries"):
    forAll(Frame4sGenerators.optionalDouble, Frame4sGenerators.rows, Frame4sGenerators.batchSize):
      (first, generated, batchSize) =>
        val values = first +: generated.map(_.value)
        val reference = value(SourceRef.values("statistics-law", "statistics-law"))
        val input = value(Frame.values[StatisticInput](reference))
        val query: Frame[StatisticOutput] =
          input
            .groupBy(_ => EmptyTuple)
            .aggregate: row =>
              (
                Aggregate.max(row.col("value").sqrt).as("root"),
                Aggregate.variancePop(row.col("value")).as("variancePop"),
                Aggregate.stddevPop(row.col("value")).as("stddevPop")
              )
        val oneBatch = value(Table.fromRows[StatisticInput](values.map(Tuple1.apply), values.size))
        val manyBatches = value(Table.fromRows[StatisticInput](values.map(Tuple1.apply), batchSize))
        try
          val firstAttempt =
            ReferenceBackend.execute(query, ReferenceSources.empty.bind(reference, oneBatch))
          val secondAttempt =
            ReferenceBackend.execute(query, ReferenceSources.empty.bind(reference, manyBatches))
          assertAttemptsEquivalent(firstAttempt, secondAttempt)
        finally
          oneBatch.close()
          manyBatches.close()

  property("typed and dynamic construction agree whenever both are valid"):
    forAll(Frame4sGenerators.intValue): threshold =>
      type Input = (id: Int, label: String)
      val typedSource = value(Frame.source[Input]("typed-dynamic-law"))
      val typed = typedSource
        .filter(row => row.col("id") === Expr.literal(threshold))
        .select(row => Tuple1(row.col("id").as("id")))

      val dynamicSource = typedSource.dynamic
      val id = value(dynamicSource.col("id"))
      val predicate = value(id === DynamicExpr.literal(LiteralValue.Int32(threshold)))
      val filtered = value(dynamicSource.filter(predicate))
      val filteredId = value(filtered.col("id"))
      val dynamic = filtered.select("id" -> filteredId)

      assertEquals(dynamic.map(_.schema), Right(typed.schema))
      assertEquals(dynamic.map(_.explain), Right(typed.explain))

  property("retained storage slices release each physical owner exactly once"):
    forAll(Frame4sGenerators.rows.suchThat(_.nonEmpty)): rows =>
      val tracker = new BufferTracker
      val values = rows.map(_.id).toArray
      val valid = Array.tabulate(values.length)(index => index % 2 != 0)
      val array = value(ColumnArray.int32(values, valid, tracker))
      val middle = value(array.slice(values.length / 3, math.max(1, values.length / 2)))
      array.close()
      array.close()
      assertEquals(tracker.snapshot.releasedOwners, 0L)
      middle.close()
      middle.close()
      assertEquals(tracker.snapshot.activeOwners, 0)
      assertEquals(tracker.snapshot.activeViews, 0)
      assertEquals(tracker.snapshot.releasedOwners, 2L)

  property("dictionary and direct UTF-8 encodings are observationally equivalent"):
    forAll(Frame4sGenerators.rows): rows =>
      val values = rows.map(_.key)
      val dictionaryValues = values.flatten.distinct
      val dictionaryIndex = dictionaryValues.zipWithIndex.toMap
      val direct = value(
        ColumnArray.utf8(
          values.map(_.getOrElse("")).toArray,
          values.map(_.nonEmpty).toArray
        )
      )
      val dictionary = value(ColumnArray.utf8(dictionaryValues.toArray))
      val indices = value(
        ColumnArray.int32(
          values.map(_.fold(0)(dictionaryIndex)).toArray,
          values.map(_.nonEmpty).toArray
        )
      )
      val encoded = value(ColumnArray.dictionary(indices, dictionary))
      try
        val directValues =
          Vector.tabulate(values.size)(index => value(direct.scalar(index)))
        val encodedValues =
          Vector.tabulate(values.size)(index => value(encoded.scalar(index)))
        assertEquals(
          directValues.map(ObservedTable.scalarFingerprint),
          encodedValues.map(ObservedTable.scalarFingerprint)
        )
      finally
        direct.close()
        encoded.close()

  test("backend capability gaps are explicit residuals"):
    type Input = (id: Int)
    val frame = value(Frame.source[Input]("residual-law"))
    val unsupported = new ExecutionBackend:
      val name = "scan-only-example"

      def execute[S <: scala.NamedTuple.AnyNamedTuple](
          input: Frame[S],
          sources: ReferenceSources
      )(using SchemaDescriptor[S]): BackendAttempt =
        BackendAttempt.Residual(
          BackendCapability.Aggregate,
          "aggregate kernel is not installed",
          BackendReceipt(name, input.explain, fallback = None)
        )

    BackendConformance.compare(frame, ReferenceSources.empty, unsupported) match
      case BackendComparison.Residual(BackendCapability.Aggregate, reason, receipt) =>
        assertEquals(reason, "aggregate kernel is not installed")
        assertEquals(receipt.fallback, None)
      case other => fail(s"expected an explicit residual, found $other")

  property("CSV split generators deliberately cover hostile boundaries"):
    forAll(Frame4sGenerators.csvChunks): fixture =>
      assertEquals(fixture.chunks.mkString, fixture.text)
      assert(
        Set(
          "CRLF and quoted delimiter",
          "escaped quote",
          "multibyte UTF-8"
        ).contains(fixture.boundary)
      )

  private def assertAttemptsEquivalent(
      left: BackendAttempt,
      right: BackendAttempt
  ): Unit =
    (left, right) match
      case (
            BackendAttempt.Completed(Left(first), _),
            BackendAttempt.Completed(Left(second), _)
          ) =>
        assertEquals(first, second)
      case (
            BackendAttempt.Completed(Right(first), _),
            BackendAttempt.Completed(Right(second), _)
          ) =>
        assert(first.equivalentTo(second))
      case other => fail(s"execution success boundary changed: $other")

  private def completed(
      attempt: BackendAttempt
  ): (ObservedTable, BackendReceipt) = attempt match
    case BackendAttempt.Completed(Right(observed), receipt) => observed -> receipt
    case other => fail(s"backend execution failed: $other")

  private def canonicalDouble(value: Double): String =
    if value.isNaN then "NaN"
    else if value == 0.0 then "0"
    else java.lang.Double.doubleToLongBits(value).toString

  private def canonicalInputRow(row: ConformanceRow): String =
    s"${row.id}|${row.key}|${row.value.map(canonicalDouble)}|${row.atMicros}"

  private def canonicalObservedRow(row: Vector[ScalarValue]): String = row match
    case Vector(
          ScalarValue.Int32(id),
          key,
          value,
          ScalarValue.Timestamp(at, TimeUnit.Microsecond)
        ) =>
      val observedKey = key match
        case ScalarValue.Null         => None
        case ScalarValue.Utf8(actual) => Some(actual)
        case other                    => fail(s"unexpected key scalar: $other")
      val observedValue = value match
        case ScalarValue.Null            => None
        case ScalarValue.Float64(actual) => Some(canonicalDouble(actual))
        case other                       => fail(s"unexpected value scalar: $other")
      s"$id|$observedKey|$observedValue|$at"
    case other => fail(s"unexpected conformance row: $other")

  private def optionalInt(row: Vector[ScalarValue]): Option[Int] = row match
    case Vector(ScalarValue.Null)         => None
    case Vector(ScalarValue.Int32(value)) => Some(value)
    case other                            => fail(s"unexpected optional-int row: $other")

  private def optionalLeftKeys(values: Vector[Option[Int]]): Table[LeftKeys] =
    optionalKeyTable[LeftKeys](values)

  private def optionalRightKeys(values: Vector[Option[Int]]): Table[RightKeys] =
    optionalKeyTable[RightKeys](values)

  private def optionalKeyTable[S <: scala.NamedTuple.AnyNamedTuple](
      values: Vector[Option[Int]]
  )(using descriptor: SchemaDescriptor[S]): Table[S] =
    val column = value(
      ColumnArray.int32(
        values.map(_.getOrElse(0)).toArray,
        values.map(_.nonEmpty).toArray
      )
    )
    val batch = RecordBatch(descriptor.schema, Vector(column)) match
      case Right(actual) => actual
      case Left(error)   =>
        column.close()
        fail(error.message)
    value(Table.takeOwnership[S](Vector(batch)))
