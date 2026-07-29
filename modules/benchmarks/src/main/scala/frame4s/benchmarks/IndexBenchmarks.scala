package frame4s.benchmarks

import frame4s.{
  ColumnArray,
  FrameError,
  Int32RowSelection,
  Int32SecondaryIndex,
  RecordBatch,
  ReferenceSources,
  Schema,
  SchemaDescriptor,
  SecondaryIndexError,
  SecondaryIndexLayout,
  SourceRef,
  StorageError,
  Table
}
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

final private[benchmarks] case class Int32LookupResult private (
    values: Array[Int],
    length: Int
):
  def checksum: Long =
    var hash = length.toLong
    var index = 0
    while index < length do
      hash = hash * 31L + values(index).toLong
      index += 1
    hash

private[benchmarks] object Int32LookupResult:
  def buffer(capacity: Int): Array[Int] =
    new Array[Int](capacity)

  def from(values: Array[Int], length: Int): Int32LookupResult =
    Int32LookupResult(values, length)

@State(Scope.Benchmark)
class IndexScanState:
  @Param(Array("100000", "1000000"))
  var rows: Int = 0

  var values: Array[Int] = Array.emptyIntArray
  var singleTarget: Int = 0
  var batchTargets: Array[Int] = Array.emptyIntArray
  var batchSize: Int = 0
  private var singleMatches: Int = 0
  private var batchMatches: Int = 0

  @Setup(Level.Trial)
  def setup(): Unit =
    configureFixture()
    singleMatches = countMatches(singleTarget)
    batchMatches = countBatchMatches()

  protected def configureFixture(): Unit =
    val multiplier = 104729L
    values = Array.tabulate(rows): position =>
      ((position.toLong * multiplier) % rows.toLong).toInt
    singleTarget = rows - 1
    val targets = Vector.tabulate(32): index =>
      val position = math.round(index.toDouble * (rows - 1).toDouble / 31.0).toInt
      values(position)
    batchTargets = targets.distinct.toArray
    scala.util.Sorting.quickSort(batchTargets)
    batchSize = batchTargets.length

  def scanSingle(): Int32LookupResult =
    val output = Int32LookupResult.buffer(singleMatches)
    var position = 0
    var outputPosition = 0
    while position < values.length do
      val value = values(position)
      if value == singleTarget then
        output(outputPosition) = value
        outputPosition += 1
      position += 1
    if outputPosition != singleMatches then
      throw new IllegalStateException(
        s"lookup key $singleTarget matched $outputPosition rows"
      )
    Int32LookupResult.from(output, outputPosition)

  def scanBatch(): Int32LookupResult =
    val output = Int32LookupResult.buffer(batchMatches)
    var position = 0
    var outputPosition = 0
    while position < values.length do
      val value = values(position)
      if containsBatchTarget(value) then
        output(outputPosition) = value
        outputPosition += 1
      position += 1
    Int32LookupResult.from(output, outputPosition)

  private def countMatches(target: Int): Int =
    var matches = 0
    var position = 0
    while position < values.length do
      if values(position) == target then matches += 1
      position += 1
    matches

  private def countBatchMatches(): Int =
    var matches = 0
    var position = 0
    while position < values.length do
      if containsBatchTarget(values(position)) then matches += 1
      position += 1
    matches

  private def containsBatchTarget(value: Int): Boolean =
    var low = 0
    var high = batchTargets.length - 1
    var found = false
    while low <= high && !found do
      val middle = (low + high) >>> 1
      val candidate = batchTargets(middle)
      if candidate < value then low = middle + 1
      else if candidate > value then high = middle - 1
      else found = true
    found

@State(Scope.Benchmark)
class IndexLookupState extends IndexScanState:
  type Row = (id: Int)

  private var table: Option[Table[Row]] = None
  private var reference: Option[SourceRef] = None
  private var sources: Option[ReferenceSources] = None
  private var index: Option[Int32SecondaryIndex] = None

  protected def indexLayout: SecondaryIndexLayout =
    SecondaryIndexLayout.FastHash

  @Setup(Level.Trial)
  override def setup(): Unit =
    super.setup()
    val schema = summon[SchemaDescriptor[Row]].schema
    val sourceTable = storage:
      Table[Row](
        Vector(
          storage:
            RecordBatch(
              schema,
              Vector(storage(ColumnArray.int32(values)))
            )
        )
      )
    val sourceReference = frame:
      SourceRef.values(
        s"secondary-index-$rows",
        s"secondary-index-$rows"
      )
    val sourceBindings = ReferenceSources.empty.bind(sourceReference, sourceTable)
    table = Some(sourceTable)
    reference = Some(sourceReference)
    sources = Some(sourceBindings)
    index = Some(build(sourceBindings, sourceReference, schema))

  @TearDown(Level.Trial)
  def tearDown(): Unit =
    index.foreach(_.close())
    table.foreach(_.close())
    index = None
    table = None
    reference = None
    sources = None

  def indexedSingle(): Int32LookupResult =
    materialize(indexValue.lookup(singleTarget))

  def indexedBatchCompatibleSingle(): Int32LookupResult =
    materialize(indexValue.lookup(Array(singleTarget)))

  def indexedBatch(): Int32LookupResult =
    materialize(indexValue.lookup(batchTargets))

  def indexedSortedBatchControl(): Int32LookupResult =
    materialize(indexValue.lookupSortedBatchControl(batchTargets))

  def buildIndex(): Long =
    val built = build(sourcesValue, referenceValue, tableValue.schema)
    try built.ownedBytes
    finally built.close()

  private def materialize(
      result: Either[SecondaryIndexError, Int32RowSelection]
  ): Int32LookupResult =
    val selection = indexResult(result)
    val output = new Array[Int](selection.size)
    var position = 0
    while position < output.length do
      output(position) = values(selection.unsafeOrdinal(position))
      position += 1
    Int32LookupResult.from(output, output.length)

  private def build(
      sourceBindings: ReferenceSources,
      sourceReference: SourceRef,
      schema: Schema
  ): Int32SecondaryIndex =
    indexResult(
      Int32SecondaryIndex.build(
        sourceBindings,
        sourceReference,
        schema,
        0,
        indexLayout
      )
    )

  private def tableValue: Table[Row] =
    table.getOrElse(throw new IllegalStateException("benchmark table is not initialized"))

  private def referenceValue: SourceRef =
    reference.getOrElse(
      throw new IllegalStateException("benchmark source reference is not initialized")
    )

  private def sourcesValue: ReferenceSources =
    sources.getOrElse(
      throw new IllegalStateException("benchmark source bindings are not initialized")
    )

  private def indexValue: Int32SecondaryIndex =
    index.getOrElse(throw new IllegalStateException("benchmark index is not initialized"))

  private def storage[A](result: Either[StorageError, A]): A =
    result.fold(error => throw new IllegalStateException(error.message), identity)

  private def frame[A](result: Either[FrameError, A]): A =
    result.fold(error => throw new IllegalStateException(error.message), identity)

  private def indexResult[A](result: Either[SecondaryIndexError, A]): A =
    result.fold(error => throw new IllegalStateException(error.message), identity)

@State(Scope.Benchmark)
class CompactIndexLookupState extends IndexLookupState:
  override protected def indexLayout: SecondaryIndexLayout =
    SecondaryIndexLayout.CompactSorted

@State(Scope.Benchmark)
class ExtendedIndexLookupState extends IndexLookupState:
  @Param(
    Array(
      "fast-hash",
      "compact-sorted",
      "packed-sorted",
      "flat-hash-rows",
      "grouped-hash"
    )
  )
  var layoutName: String = ""

  @Param(Array("unique", "mixed-miss", "fanout-8", "skewed"))
  var shape: String = ""

  override protected def indexLayout: SecondaryIndexLayout =
    layoutName match
      case "fast-hash"      => SecondaryIndexLayout.FastHash
      case "compact-sorted" => SecondaryIndexLayout.CompactSorted
      case "packed-sorted"  => SecondaryIndexLayout.PackedSorted
      case "flat-hash-rows" => SecondaryIndexLayout.FlatHashRows
      case "grouped-hash"   => SecondaryIndexLayout.GroupedHash
      case unexpected       =>
        throw new IllegalArgumentException(s"unknown index layout: $unexpected")

  override protected def configureFixture(): Unit =
    val multiplier = 104729L
    values = Array.tabulate(rows): position =>
      ((position.toLong * multiplier) % rows.toLong).toInt
    shape match
      case "unique" =>
        singleTarget = rows - 1
        batchTargets = distributedTargets(32)
      case "mixed-miss" =>
        singleTarget = -1
        batchTargets = distributedTargets(16) ++ Array.tabulate(16)(index => -1 - index)
      case "fanout-8" =>
        var position = 0
        while position < values.length do
          values(position) = values(position) / 8
          position += 1
        singleTarget = values(rows - 1)
        batchTargets = distributedTargets(32)
      case "skewed" =>
        var position = 0
        while position < values.length do
          if position % 128 == 0 then values(position) = -1
          position += 1
        singleTarget = -1
        batchTargets = Array(-1) ++ distributedTargets(31).filter(_ != -1)
      case unexpected =>
        throw new IllegalArgumentException(s"unknown index shape: $unexpected")
    batchTargets = batchTargets.distinct
    scala.util.Sorting.quickSort(batchTargets)
    batchSize = batchTargets.length

  private def distributedTargets(count: Int): Array[Int] =
    Vector
      .tabulate(count): index =>
        val denominator = math.max(1, count - 1)
        val position =
          math.round(index.toDouble * (rows - 1).toDouble / denominator.toDouble).toInt
        values(position)
      .distinct
      .toArray

@State(Scope.Benchmark)
class FlatAllEqualBuildState extends IndexLookupState:
  @Param(Array("flat-hash-rows", "grouped-hash"))
  var layoutName: String = ""

  @Param(Array("all-equal"))
  var shape: String = ""

  override protected def indexLayout: SecondaryIndexLayout =
    layoutName match
      case "flat-hash-rows" => SecondaryIndexLayout.FlatHashRows
      case "grouped-hash"   => SecondaryIndexLayout.GroupedHash
      case unexpected       =>
        throw new IllegalArgumentException(s"unknown index layout: $unexpected")

  override protected def configureFixture(): Unit =
    values = Array.fill(rows)(7)
    singleTarget = 7
    batchTargets = Array(7)
    batchSize = 1

@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"))
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
class IndexScanCourt:
  @Benchmark
  def singleLookup(state: IndexScanState): Long =
    state.scanSingle().checksum

  @Benchmark
  def batch32Lookup(state: IndexScanState): Long =
    state.scanBatch().checksum

@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"))
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
class IndexLookupCourt:
  @Benchmark
  def singleLookup(state: IndexLookupState): Long =
    state.indexedSingle().checksum

  @Benchmark
  def batch32Lookup(state: IndexLookupState): Long =
    state.indexedBatch().checksum

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"))
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
class IndexBuildCourt:
  @Benchmark
  def build(state: IndexLookupState): Long =
    state.buildIndex()

@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"))
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
class CompactIndexLookupCourt:
  @Benchmark
  def singleLookup(state: CompactIndexLookupState): Long =
    state.indexedSingle().checksum

  @Benchmark
  def batch32Lookup(state: CompactIndexLookupState): Long =
    state.indexedBatch().checksum

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"))
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
class CompactIndexBuildCourt:
  @Benchmark
  def build(state: CompactIndexLookupState): Long =
    state.buildIndex()

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"))
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
class IndexLegacyControlCourt:
  @Benchmark
  def singleLookup(state: IndexLookupState): Long =
    state.indexedBatchCompatibleSingle().checksum

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"))
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
class ExtendedIndexLookupCourt:
  @Benchmark
  def singleLookup(state: ExtendedIndexLookupState): Long =
    state.indexedSingle().checksum

  @Benchmark
  def batch32Lookup(state: ExtendedIndexLookupState): Long =
    state.indexedBatch().checksum

  @Benchmark
  def sortedBatchControl(state: ExtendedIndexLookupState): Long =
    state.indexedSortedBatchControl().checksum

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"))
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
class ExtendedIndexBuildCourt:
  @Benchmark
  def build(state: ExtendedIndexLookupState): Long =
    state.buildIndex()

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"))
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
class FlatAllEqualBuildCourt:
  @Benchmark
  def build(state: FlatAllEqualBuildState): Long =
    state.buildIndex()
