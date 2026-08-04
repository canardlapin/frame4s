package frame4s.benchmarks

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import frame4s.*
import frame4s.fs2.*
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

private[benchmarks] object BenchmarkSupport:
  def storage[A](result: Either[StorageError, A]): A =
    result.fold(error => throw new IllegalStateException(error.message), identity)

  def frame[A](result: Either[FrameError, A]): A =
    result.fold(error => throw new IllegalStateException(error.message), identity)

  def execution[A](result: Either[ExecutionError, A]): A =
    result.fold(error => throw new IllegalStateException(error.message), identity)

  def collect[S <: scala.NamedTuple.AnyNamedTuple](
      frame: Frame[S],
      sources: ReferenceSources
  )(using SchemaDescriptor[S]): Table[S] =
    execution(ReferenceInterpreter.prepare(frame.plan, sources).collect[S])

  def scalarHash(value: ScalarValue): Long = value match
    case ScalarValue.Null            => 0x61c8864680b583ebL
    case ScalarValue.Bool(actual)    => if actual then 1L else 2L
    case ScalarValue.Int32(actual)   => actual.toLong
    case ScalarValue.Int64(actual)   => actual
    case ScalarValue.Float32(actual) =>
      java.lang.Float.floatToRawIntBits(actual).toLong
    case ScalarValue.Float64(actual) =>
      java.lang.Double.doubleToRawLongBits(actual)
    case ScalarValue.Utf8(actual)            => actual.hashCode.toLong
    case ScalarValue.Timestamp(actual, unit) => actual ^ unit.ordinal.toLong

  def checksum[S <: scala.NamedTuple.AnyNamedTuple](table: Table[S]): Long =
    var hash = table.rowCount
    val batches = table.batches.iterator
    while batches.hasNext do
      val batch = batches.next()
      var row = 0
      while row < batch.rowCount do
        val columns = batch.columns.iterator
        while columns.hasNext do hash = hash * 31L + scalarHash(storage(columns.next().scalar(row)))
        row += 1
    hash

  def collectChecksum[S <: scala.NamedTuple.AnyNamedTuple](
      frame: Frame[S],
      sources: ReferenceSources
  )(using SchemaDescriptor[S]): Long =
    val table = collect(frame, sources)
    try checksum(table)
    finally table.close()

@State(org.openjdk.jmh.annotations.Scope.Benchmark)
class ReferenceState:
  import BenchmarkSupport.*

  type Facts = (id: Int, group: String, value: Option[Double])
  type Primitive = (id: Int)
  type Nullable = (value: Option[Double])
  type FilterProject = (id: Int, next: Int)
  type GroupedString = (
      group: String,
      n: Long,
      sum: Option[Double],
      mean: Option[Double],
      variancePop: Option[Double]
  )
  type GroupedSum = (group: String, sum: Option[Double])
  type GroupedInt = (id: Int, n: Long, sum: Option[Double])
  type JoinLeft = (key: Int, leftValue: Long)
  type JoinRight = (rightKey: Int, rightValue: Long)
  type Joined = (key: Int, leftValue: Long, rightKey: Int, rightValue: Long)
  type RightKeyOnly = (rightKey: Int)

  @Param(Array("10000"))
  var rows: Int = 0

  var sources: ReferenceSources = ReferenceSources.empty
  var factsTable: Table[Facts] = scala.compiletime.uninitialized
  var joinLeftTable: Table[JoinLeft] = scala.compiletime.uninitialized
  var joinOneTable: Table[JoinRight] = scala.compiletime.uninitialized
  var joinManyTable: Table[JoinRight] = scala.compiletime.uninitialized
  var joinSparseTable: Table[JoinRight] = scala.compiletime.uninitialized
  var joinSkewTable: Table[JoinRight] = scala.compiletime.uninitialized
  var utf8Column: ColumnArray = scala.compiletime.uninitialized
  var dictionaryColumn: ColumnArray = scala.compiletime.uninitialized

  var primitiveScan: Frame[Primitive] = scala.compiletime.uninitialized
  var nullableScan: Frame[Nullable] = scala.compiletime.uninitialized
  var filterOnly: Frame[Facts] = scala.compiletime.uninitialized
  var filterProject: Frame[FilterProject] = scala.compiletime.uninitialized
  var groupLow: Frame[GroupedString] = scala.compiletime.uninitialized
  var groupLowSum: Frame[GroupedSum] = scala.compiletime.uninitialized
  var groupHigh: Frame[GroupedInt] = scala.compiletime.uninitialized
  var joinOne: Frame[Joined] = scala.compiletime.uninitialized
  var joinMany: Frame[Joined] = scala.compiletime.uninitialized
  var joinSparse: Frame[Joined] = scala.compiletime.uninitialized
  var joinSkew: Frame[Joined] = scala.compiletime.uninitialized
  var distinctLow: Frame[RightKeyOnly] = scala.compiletime.uninitialized
  var semiSparse: Frame[JoinLeft] = scala.compiletime.uninitialized
  var antiSparse: Frame[JoinLeft] = scala.compiletime.uninitialized
  var unionAllRows: Frame[JoinLeft] = scala.compiletime.uninitialized
  var csv: String = ""

  private[benchmarks] var columnarPrimitive: ColumnarExecution =
    scala.compiletime.uninitialized
  private[benchmarks] var columnarNullable: ColumnarExecution =
    scala.compiletime.uninitialized
  private[benchmarks] var columnarFilter: ColumnarExecution =
    scala.compiletime.uninitialized
  private[benchmarks] var columnarFused: ColumnarExecution =
    scala.compiletime.uninitialized
  private[benchmarks] var columnarGroupLow: ColumnarExecution =
    scala.compiletime.uninitialized
  private[benchmarks] var columnarGroupLowSum: ColumnarExecution =
    scala.compiletime.uninitialized
  private[benchmarks] var columnarGroupHigh: ColumnarExecution =
    scala.compiletime.uninitialized
  private[benchmarks] var columnarJoinOne: ColumnarExecution =
    scala.compiletime.uninitialized
  private[benchmarks] var columnarJoinMany: ColumnarExecution =
    scala.compiletime.uninitialized
  private[benchmarks] var columnarJoinSparse: ColumnarExecution =
    scala.compiletime.uninitialized
  private[benchmarks] var columnarJoinSkew: ColumnarExecution =
    scala.compiletime.uninitialized
  private[benchmarks] var columnarDistinctLow: ColumnarExecution =
    scala.compiletime.uninitialized
  private[benchmarks] var columnarSemiSparse: ColumnarExecution =
    scala.compiletime.uninitialized
  private[benchmarks] var columnarAntiSparse: ColumnarExecution =
    scala.compiletime.uninitialized
  private[benchmarks] var columnarUnionAll: ColumnarExecution =
    scala.compiletime.uninitialized

  private def valuesRef(id: String): SourceRef =
    frame(SourceRef.values(id, id))

  private def facts(
      ids: Array[Int],
      groups: Array[String],
      values: Array[Double],
      valid: Array[Boolean]
  ): Table[Facts] =
    val schema = summon[SchemaDescriptor[Facts]].schema
    storage:
      Table.takeOwnership[Facts](
        Vector(
          storage:
            RecordBatch(
              schema,
              Vector(
                storage(ColumnArray.int32(ids)),
                storage(ColumnArray.utf8(groups)),
                storage(ColumnArray.float64(values, valid))
              )
            )
        )
      )

  private def joinLeft(
      keys: Array[Int],
      values: Array[Long]
  ): Table[JoinLeft] =
    val schema = summon[SchemaDescriptor[JoinLeft]].schema
    storage:
      Table.takeOwnership[JoinLeft](
        Vector(
          storage:
            RecordBatch(
              schema,
              Vector(
                storage(ColumnArray.int32(keys)),
                storage(ColumnArray.int64(values))
              )
            )
        )
      )

  private def joinRight(
      keys: Array[Int],
      values: Array[Long]
  ): Table[JoinRight] =
    val schema = summon[SchemaDescriptor[JoinRight]].schema
    storage:
      Table.takeOwnership[JoinRight](
        Vector(
          storage:
            RecordBatch(
              schema,
              Vector(
                storage(ColumnArray.int32(keys)),
                storage(ColumnArray.int64(values))
              )
            )
        )
      )

  @Setup(Level.Trial)
  def setup(): Unit =
    val ids = Array.tabulate(rows)(identity)
    val groups = Array.tabulate(rows)(index => s"g${index % 16}")
    val values = Array.tabulate(rows)(index => index.toDouble / 8.0)
    val valid = Array.tabulate(rows)(index => index % 7 != 0)
    val factsRef = valuesRef("bench-facts")
    val leftRef = valuesRef("bench-left")
    val oneRef = valuesRef("bench-right-one")
    val manyRef = valuesRef("bench-right-many")
    val sparseRef = valuesRef("bench-right-sparse")
    val skewRef = valuesRef("bench-right-skew")
    val unionRef = valuesRef("bench-union-right")

    factsTable = facts(ids, groups, values, valid)
    joinLeftTable = joinLeft(ids, Array.tabulate(rows)(_.toLong))
    joinOneTable = joinRight(ids, Array.tabulate(rows)(index => index.toLong * 2L))
    joinManyTable = joinRight(
      Array.tabulate(rows)(index => index / 3),
      Array.tabulate(rows)(index => index.toLong * 3L)
    )
    joinSparseTable = joinRight(
      Array.tabulate(math.max(1, rows / 10))(index => index * 10),
      Array.tabulate(math.max(1, rows / 10))(_.toLong)
    )
    joinSkewTable = joinRight(
      Array.fill(math.max(1, math.min(rows, 1000)))(0),
      Array.tabulate(math.max(1, math.min(rows, 1000)))(_.toLong)
    )

    val factsFrame = frame(Frame.values[Facts](factsRef))
    primitiveScan = factsFrame.select(row => Tuple1(row.col("id").as("id")))
    nullableScan = factsFrame.select(row => Tuple1(row.col("value").as("value")))
    filterOnly = factsFrame.filter(row => row.col("id") >= Expr.literal(rows / 2))
    filterProject = factsFrame
      .filter(row => row.col("id") >= Expr.literal(rows / 2))
      .select: row =>
        (
          row.col("id").as("id"),
          (row.col("id") + Expr.literal(1)).as("next")
        )
    groupLow = factsFrame
      .groupBy(row => Tuple1(row.col("group").as("group")))
      .aggregate: row =>
        (
          Aggregate.count.as("n"),
          Aggregate.sum(row.col("value")).as("sum"),
          Aggregate.mean(row.col("value")).as("mean"),
          Aggregate.variancePop(row.col("value")).as("variancePop")
        )
    groupLowSum = factsFrame
      .groupBy(row => Tuple1(row.col("group").as("group")))
      .aggregate: row =>
        Tuple1(Aggregate.sum(row.col("value")).as("sum"))
    groupHigh = factsFrame
      .groupBy(row => Tuple1(row.col("id").as("id")))
      .aggregate: row =>
        (
          Aggregate.count.as("n"),
          Aggregate.sum(row.col("value")).as("sum")
        )

    val left = frame(Frame.values[JoinLeft](leftRef))
    val one = frame(Frame.values[JoinRight](oneRef))
    val many = frame(Frame.values[JoinRight](manyRef))
    val sparse = frame(Frame.values[JoinRight](sparseRef))
    val skew = frame(Frame.values[JoinRight](skewRef))
    joinOne = left.innerJoin(one)((l, r) => l.col("key") === r.col("rightKey"))
    joinMany = left.innerJoin(many)((l, r) => l.col("key") === r.col("rightKey"))
    joinSparse = left.innerJoin(sparse)((l, r) => l.col("key") === r.col("rightKey"))
    joinSkew = left.innerJoin(skew)((l, r) => l.col("key") === r.col("rightKey"))
    distinctLow = many
      .select(row => Tuple1(row.col("rightKey").as("rightKey")))
      .distinct
    semiSparse = left.semiJoin(sparse)((l, r) => l.col("key") === r.col("rightKey"))
    antiSparse = left.antiJoin(sparse)((l, r) => l.col("key") === r.col("rightKey"))
    val unionRight = frame(Frame.values[JoinLeft](unionRef))
    unionAllRows = left.unionAll(unionRight)

    sources = ReferenceSources.empty
      .bind(factsRef, factsTable)
      .bind(leftRef, joinLeftTable)
      .bind(oneRef, joinOneTable)
      .bind(manyRef, joinManyTable)
      .bind(sparseRef, joinSparseTable)
      .bind(skewRef, joinSkewTable)
      .bind(unionRef, joinLeftTable)

    columnarPrimitive = ColumnarInterpreter.prepare(primitiveScan.plan, sources)
    columnarNullable = ColumnarInterpreter.prepare(nullableScan.plan, sources)
    columnarFilter = ColumnarInterpreter.prepare(filterOnly.plan, sources)
    columnarFused = ColumnarInterpreter.prepare(filterProject.plan, sources)
    columnarGroupLow = ColumnarInterpreter.prepare(groupLow.plan, sources)
    columnarGroupLowSum = ColumnarInterpreter.prepare(groupLowSum.plan, sources)
    columnarGroupHigh = ColumnarInterpreter.prepare(groupHigh.plan, sources)
    columnarJoinOne = ColumnarInterpreter.prepare(joinOne.plan, sources)
    columnarJoinMany = ColumnarInterpreter.prepare(joinMany.plan, sources)
    columnarJoinSparse = ColumnarInterpreter.prepare(joinSparse.plan, sources)
    columnarJoinSkew = ColumnarInterpreter.prepare(joinSkew.plan, sources)
    columnarDistinctLow = ColumnarInterpreter.prepare(distinctLow.plan, sources)
    columnarSemiSparse = ColumnarInterpreter.prepare(semiSparse.plan, sources)
    columnarAntiSparse = ColumnarInterpreter.prepare(antiSparse.plan, sources)
    columnarUnionAll = ColumnarInterpreter.prepare(unionAllRows.plan, sources)

    utf8Column = storage(ColumnArray.utf8(groups))
    val dictionaryValues = Array.tabulate(16)(index => s"g$index")
    val dictionary = storage(ColumnArray.utf8(dictionaryValues))
    val indices = storage(ColumnArray.int32(Array.tabulate(rows)(index => index % 16)))
    dictionaryColumn = storage(ColumnArray.dictionary(indices, dictionary))

    val builder = new StringBuilder("id,group,value\n")
    var index = 0
    while index < rows do
      val _ = builder
        .append(index)
        .append(',')
        .append(groups(index))
        .append(',')
      if valid(index) then builder.append(values(index))
      builder.append('\n')
      index += 1
    csv = builder.result()

  @TearDown(Level.Trial)
  def tearDown(): Unit =
    factsTable.close()
    joinLeftTable.close()
    joinOneTable.close()
    joinManyTable.close()
    joinSparseTable.close()
    joinSkewTable.close()
    utf8Column.close()
    dictionaryColumn.close()

@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"))
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
class ReferenceBenchmarks:
  import BenchmarkSupport.*

  @Benchmark
  def primitiveScan(state: ReferenceState): Long =
    collectChecksum(state.primitiveScan, state.sources)

  @Benchmark
  def nullableScan(state: ReferenceState): Long =
    collectChecksum(state.nullableScan, state.sources)

  @Benchmark
  def filter(state: ReferenceState): Long =
    collectChecksum(state.filterOnly, state.sources)

  @Benchmark
  def fusedFilterProjectArithmetic(state: ReferenceState): Long =
    collectChecksum(state.filterProject, state.sources)

  @Benchmark
  def groupedLowCardinality(state: ReferenceState): Long =
    collectChecksum(state.groupLow, state.sources)

  @Benchmark
  def groupedLowCardinalitySumOnly(state: ReferenceState): Long =
    collectChecksum(state.groupLowSum, state.sources)

  @Benchmark
  def groupedHighCardinality(state: ReferenceState): Long =
    collectChecksum(state.groupHigh, state.sources)

  @Benchmark
  def joinOneToOne(state: ReferenceState): Long =
    collectChecksum(state.joinOne, state.sources)

  @Benchmark
  def joinOneToMany(state: ReferenceState): Long =
    collectChecksum(state.joinMany, state.sources)

  @Benchmark
  def joinSparse(state: ReferenceState): Long =
    collectChecksum(state.joinSparse, state.sources)

  @Benchmark
  def joinSkewed(state: ReferenceState): Long =
    collectChecksum(state.joinSkew, state.sources)

  @Benchmark
  def distinctLowCardinality(state: ReferenceState): Long =
    collectChecksum(state.distinctLow, state.sources)

  @Benchmark
  def semiJoinSparse(state: ReferenceState): Long =
    collectChecksum(state.semiSparse, state.sources)

  @Benchmark
  def antiJoinSparse(state: ReferenceState): Long =
    collectChecksum(state.antiSparse, state.sources)

  @Benchmark
  def unionAll(state: ReferenceState): Long =
    collectChecksum(state.unionAllRows, state.sources)

  @Benchmark
  def utf8Scan(state: ReferenceState): Long =
    scanColumn(state.utf8Column)

  @Benchmark
  def dictionaryScan(state: ReferenceState): Long =
    scanColumn(state.dictionaryColumn)

  private def scanColumn(column: ColumnArray): Long =
    var hash = 1L
    var index = 0
    while index < column.length do
      hash = hash * 31L + scalarHash(storage(column.scalar(index)))
      index += 1
    hash

  @Benchmark
  def boundedScalarDecode(state: ReferenceState): Long =
    val limit = math.min(32, state.factsTable.batches.head.rowCount)
    var hash = 1L
    var row = 0
    while row < limit do
      val columns = state.factsTable.batches.head.columns.iterator
      while columns.hasNext do hash = hash * 31L + scalarHash(storage(columns.next().scalar(row)))
      row += 1
    hash

  @Benchmark
  def tableConstruction(state: ReferenceState): Long =
    type Constructed = (id: Int, value: Option[Double])
    val schema = summon[SchemaDescriptor[Constructed]].schema
    val ids = Array.tabulate(state.rows)(identity)
    val values = Array.tabulate(state.rows)(_.toDouble)
    val valid = Array.tabulate(state.rows)(_ % 5 != 0)
    val table = storage:
      Table.takeOwnership[Constructed](
        Vector(
          storage:
            RecordBatch(
              schema,
              Vector(
                storage(ColumnArray.int32(ids)),
                storage(ColumnArray.float64(values, valid))
              )
            )
        )
      )
    try checksum(table)
    finally table.close()

  @Benchmark
  def csvDecode(state: ReferenceState): Long =
    type CsvRow = (id: Int, group: String, value: Option[Double])
    val schema = summon[SchemaDescriptor[CsvRow]].schema
    CsvFrameSource
      .resource[IO](
        state.csv,
        CsvReadOptions(schema, batchSize = 1024)
      )
      .use: source =>
        source
          .plan(ScanRequest())
          .flatMap:
            case Left(error) => IO.raiseError(new IllegalStateException(error.message))
            case Right(scan) =>
              scan.batches
                .evalMap: batch =>
                  IO:
                    var hash = batch.rowCount.toLong
                    val columns = batch.columns.iterator
                    while columns.hasNext do hash = hash * 31L + columns.next().nullCount.toLong
                    hash
                .compile
                .fold(0L)(_ ^ _)
      .unsafeRunSync()

@State(org.openjdk.jmh.annotations.Scope.Benchmark)
class SaddleState:
  @Param(Array("10000"))
  var rows: Int = 0

  var ids: org.saddle.Series[Int, Int] = scala.compiletime.uninitialized
  var grouped: org.saddle.Series[String, Double] = scala.compiletime.uninitialized
  var groupedNullable: org.saddle.Series[String, Double] = scala.compiletime.uninitialized
  var groupOrder: Array[String] = Array.empty[String]

  @Setup(Level.Trial)
  def setup(): Unit =
    ids = org.saddle.Series(org.saddle.Vec(Array.tabulate(rows)(identity)))
    val raw = Array.tabulate(rows)(index => index.toDouble / 8.0)
    grouped = org.saddle.Series(
      org.saddle.Vec(raw),
      org.saddle.Index(Array.tabulate(rows)(index => s"g${index % 16}"))
    )
    val nullableRaw = Array.tabulate(rows): index =>
      if index % 7 == 0 then Double.NaN else index.toDouble / 8.0
    groupedNullable = org.saddle.Series(
      org.saddle.Vec(nullableRaw),
      org.saddle.Index(Array.tabulate(rows)(index => s"g${index % 16}"))
    )
    groupOrder = Array.tabulate(math.min(16, rows))(index => s"g$index")

@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"))
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
class SaddleBenchmarks:
  import org.saddle.doubleOrd

  @Benchmark
  def primitiveScan(state: SaddleState): Long =
    var hash = state.ids.length.toLong
    var index = 0
    while index < state.ids.length do
      hash = hash * 31L + state.ids.raw(index).toLong
      index += 1
    hash

  @Benchmark
  def primitiveMaterializedProjection(state: SaddleState): Long =
    val projected = state.ids.mapValues(identity)
    var hash = projected.length.toLong
    var index = 0
    while index < projected.length do
      hash = hash * 31L + projected.raw(index).toLong
      index += 1
    hash

  @Benchmark
  def filterProjectArithmetic(state: SaddleState): Long =
    val filtered = state.ids.filter(_ >= state.rows / 2)
    val next = filtered.mapValues(_ + 1)
    var hash = filtered.length.toLong
    var index = 0
    while index < filtered.length do
      hash = hash * 31L + filtered.raw(index).toLong
      hash = hash * 31L + next.raw(index).toLong
      index += 1
    hash

  @Benchmark
  def groupedLowCardinality(state: SaddleState): Double =
    state.grouped.groupBy.combine(_.sum).sum

  @Benchmark
  def groupedLowCardinalitySumOnly(state: SaddleState): Long =
    val grouped = state.groupedNullable.groupBy.combine(_.sum)
    var hash = grouped.length.toLong
    var index = 0
    while index < state.groupOrder.length do
      val key = state.groupOrder(index)
      hash = hash * 31L + key.hashCode.toLong
      hash = hash * 31L + java.lang.Double.doubleToRawLongBits(grouped.getRaw(key))
      index += 1
    hash

@State(org.openjdk.jmh.annotations.Scope.Benchmark)
class ArrayState:
  @Param(Array("10000"))
  var rows: Int = 0

  var ids: Array[Int] = Array.emptyIntArray
  var values: Array[Double] = Array.emptyDoubleArray
  var valid: Array[Boolean] = Array.emptyBooleanArray

  @Setup(Level.Trial)
  def setup(): Unit =
    ids = Array.tabulate(rows)(identity)
    values = Array.tabulate(rows)(index => index.toDouble / 8.0)
    valid = Array.tabulate(rows)(index => index % 7 != 0)

@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"))
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
class SpecializedArrayBenchmarks:
  @Benchmark
  def nullableScan(state: ArrayState): Long =
    var hash = state.rows.toLong
    var index = 0
    while index < state.rows do
      val value =
        if state.valid(index) then java.lang.Double.doubleToRawLongBits(state.values(index))
        else 0x61c8864680b583ebL
      hash = hash * 31L + value
      index += 1
    hash

  @Benchmark
  def fusedFilterProjectArithmetic(state: ArrayState): Long =
    var hash = (state.rows - state.rows / 2).toLong
    var index = state.rows / 2
    while index < state.rows do
      hash = hash * 31L + state.ids(index).toLong
      hash = hash * 31L + state.ids(index).toLong + 1L
      index += 1
    hash
