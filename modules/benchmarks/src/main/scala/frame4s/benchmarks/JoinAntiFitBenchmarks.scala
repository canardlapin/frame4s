package frame4s.benchmarks

import frame4s.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

private[benchmarks] object JoinAntiFitFixture:
  val Workloads = Vector(
    "aligned-unique",
    "offset-unique",
    "interleaved-gaps",
    "disjoint-sorted",
    "duplicate-groups",
    "sparse-sorted",
    "nullable-key",
    "multibatch-sorted",
    "fully-shuffled",
    "late-inversion"
  )

  def rightRows(rows: Int, workload: String): Int =
    if workload == "sparse-sorted" then math.max(1, rows / 10)
    else rows

  def keys(rows: Int, workload: String, left: Boolean): Array[Option[Int]] =
    val count = if left then rows else rightRows(rows, workload)
    val values =
      workload match
        case "aligned-unique" | "multibatch-sorted" =>
          Array.tabulate(count)(identity)
        case "offset-unique" =>
          val offset = rows / 4
          Array.tabulate(count)(index => if left then index else index + offset)
        case "interleaved-gaps" =>
          Array.tabulate(count)(index => if left then index * 2 else index * 3)
        case "disjoint-sorted" =>
          Array.tabulate(count)(index => if left then index else index + rows)
        case "duplicate-groups" =>
          Array.tabulate(count)(index => index / 2)
        case "sparse-sorted" =>
          Array.tabulate(count)(index => if left then index else index * 10)
        case "nullable-key" =>
          Array.tabulate(count)(identity)
        case "fully-shuffled" =>
          JoinRegimeFixture.permutation(
            count,
            if left then JoinRegimeFixture.LeftSeed else JoinRegimeFixture.RightSeed
          )
        case "late-inversion" =>
          val output = Array.tabulate(count)(identity)
          if !left && count >= 2 then
            val swap = output(count - 1)
            output(count - 1) = output(count - 2)
            output(count - 2) = swap
          output
        case value =>
          throw new IllegalArgumentException(s"unsupported anti-fit workload: $value")
    val output: Array[Option[Int]] = values.map(value => Option(value))
    if workload == "nullable-key" && output.nonEmpty then output(output.length / 2) = None
    output

  def outputRows(rows: Int, workload: String): Long =
    workload match
      case "aligned-unique" | "multibatch-sorted" | "fully-shuffled" | "late-inversion" =>
        rows.toLong
      case "offset-unique"    => rows.toLong - rows.toLong / 4L
      case "interleaved-gaps" => (rows.toLong + 2L) / 3L
      case "disjoint-sorted"  => 0L
      case "duplicate-groups" =>
        rows.toLong / 2L * 4L + rows.toLong % 2L
      case "sparse-sorted" => math.max(1, rows / 10).toLong
      case "nullable-key"  => math.max(0, rows - 1).toLong
      case value           =>
        throw new IllegalArgumentException(s"unsupported anti-fit workload: $value")

  def expectedChecksum(
      rows: Int,
      workload: String,
      leftKeys: Array[Option[Int]],
      rightKeys: Array[Option[Int]]
  ): Long =
    var hash = outputRows(rows, workload)

    def append(leftRow: Int, rightRow: Int): Unit =
      val key = leftKeys(leftRow).getOrElse:
        throw new IllegalStateException("analytic oracle selected a null left key")
      val rightKey = rightKeys(rightRow).getOrElse:
        throw new IllegalStateException("analytic oracle selected a null right key")
      if key != rightKey then
        throw new IllegalStateException(s"analytic oracle mismatch: $key != $rightKey")
      hash = hash * 31L + key.toLong
      hash = hash * 31L + leftRow.toLong * 3L
      hash = hash * 31L + rightKey.toLong
      hash = hash * 31L + rightRow.toLong * 5L

    workload match
      case "aligned-unique" | "multibatch-sorted" | "late-inversion" =>
        var row = 0
        while row < rows do
          val rightRow =
            if workload == "late-inversion" && rows >= 2 then
              if row == rows - 2 then rows - 1
              else if row == rows - 1 then rows - 2
              else row
            else row
          append(row, rightRow)
          row += 1
      case "offset-unique" =>
        val offset = rows / 4
        var row = offset
        while row < rows do
          append(row, row - offset)
          row += 1
      case "interleaved-gaps" =>
        var row = 0
        while row < rows do
          if row % 3 == 0 then append(row, row * 2 / 3)
          row += 1
      case "disjoint-sorted"  => ()
      case "duplicate-groups" =>
        var leftRow = 0
        while leftRow < rows do
          val first = leftRow / 2 * 2
          append(leftRow, first)
          if first + 1 < rows then append(leftRow, first + 1)
          leftRow += 1
      case "sparse-sorted" =>
        var rightRow = 0
        while rightRow < rightKeys.length do
          append(rightRow * 10, rightRow)
          rightRow += 1
      case "nullable-key" =>
        val nullRow = rows / 2
        var row = 0
        while row < rows do
          if row != nullRow then append(row, row)
          row += 1
      case "fully-shuffled" =>
        val inverse = new Array[Int](rows)
        var rightRow = 0
        while rightRow < rightKeys.length do
          inverse(rightKeys(rightRow).getOrElse(-1)) = rightRow
          rightRow += 1
        var leftRow = 0
        while leftRow < leftKeys.length do
          append(leftRow, inverse(leftKeys(leftRow).getOrElse(-1)))
          leftRow += 1
      case value =>
        throw new IllegalArgumentException(s"unsupported anti-fit workload: $value")
    hash

@State(org.openjdk.jmh.annotations.Scope.Benchmark)
class JoinAntiFitState:
  import BenchmarkSupport.*

  type Left = (key: Option[Int], leftValue: Long)
  type Right = (rightKey: Option[Int], rightValue: Long)
  type Joined = (
      key: Option[Int],
      leftValue: Long,
      rightKey: Option[Int],
      rightValue: Long
  )

  @Param(Array("1000"))
  var rows: Int = 0

  @Param(Array("aligned-unique"))
  var workload: String = ""

  private var leftTable: Table[Left] = scala.compiletime.uninitialized
  private var rightTable: Table[Right] = scala.compiletime.uninitialized
  private[benchmarks] var execution: ColumnarExecution =
    scala.compiletime.uninitialized
  private[benchmarks] var expectedRows: Long = 0L
  private[benchmarks] var expectedChecksum: Long = 0L
  private[benchmarks] var leftDigest: String = ""
  private[benchmarks] var rightDigest: String = ""

  private def valuesRef(id: String): SourceRef =
    frame(SourceRef.values(id, id))

  private def batches(
      schema: Schema,
      keys: Array[Option[Int]],
      multiplier: Long,
      split: Boolean
  ): Vector[RecordBatch] =
    val boundaries =
      if split && keys.length >= 3 then
        Vector(0, keys.length / 3, keys.length * 2 / 3 + 1, keys.length).distinct.sorted
      else Vector(0, keys.length)
    boundaries
      .sliding(2)
      .map:
        case Vector(from, until) =>
          val length = until - from
          val keyValues = new Array[Int](length)
          val valid = new Array[Boolean](length)
          val payload = new Array[Long](length)
          var row = 0
          while row < length do
            keys(from + row) match
              case Some(value) =>
                keyValues(row) = value
                valid(row) = true
              case None => ()
            payload(row) = (from + row).toLong * multiplier
            row += 1
          storage:
            RecordBatch(
              schema,
              Vector(
                storage(ColumnArray.int32(keyValues, valid)),
                storage(ColumnArray.int64(payload))
              )
            )
        case value =>
          throw new IllegalStateException(s"invalid batch boundary pair $value")
      .toVector

  @Setup(Level.Trial)
  def setup(): Unit =
    if rows <= 0 then throw new IllegalArgumentException("rows must be positive")
    if !JoinAntiFitFixture.Workloads.contains(workload) then
      throw new IllegalArgumentException(s"unsupported anti-fit workload: $workload")

    val leftKeys = JoinAntiFitFixture.keys(rows, workload, left = true)
    val rightKeys = JoinAntiFitFixture.keys(rows, workload, left = false)
    leftDigest = JoinRegimeFixture.digest(leftKeys.map(_.getOrElse(Int.MinValue)))
    rightDigest = JoinRegimeFixture.digest(rightKeys.map(_.getOrElse(Int.MinValue)))
    val split = workload == "multibatch-sorted"
    leftTable = storage:
      Table.takeOwnership[Left](
        batches(summon[SchemaDescriptor[Left]].schema, leftKeys, 3L, split)
      )
    rightTable = storage:
      Table.takeOwnership[Right](
        batches(summon[SchemaDescriptor[Right]].schema, rightKeys, 5L, split)
      )

    val leftRef = valuesRef(s"anti-fit-left-$rows-$workload")
    val rightRef = valuesRef(s"anti-fit-right-$rows-$workload")
    val left = frame(Frame.values[Left](leftRef))
    val right = frame(Frame.values[Right](rightRef))
    val joined: Frame[Joined] =
      left.innerJoin(right): (lhs, rhs) =>
        (lhs.col("key") === rhs.col("rightKey")).isTrue
    val sources =
      ReferenceSources.empty.bind(leftRef, leftTable).bind(rightRef, rightTable)
    execution = ColumnarInterpreter.prepare(joined.plan, sources)
    expectedRows = JoinAntiFitFixture.outputRows(rows, workload)
    expectedChecksum = JoinAntiFitFixture.expectedChecksum(rows, workload, leftKeys, rightKeys)

  @TearDown(Level.Trial)
  def tearDown(): Unit =
    execution.close()
    leftTable.close()
    rightTable.close()

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 2, time = 300, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 3, time = 400, timeUnit = TimeUnit.MILLISECONDS)
class JoinAntiFitBenchmarks:
  @Benchmark
  def gatherView(state: JoinAntiFitState, blackhole: Blackhole): Long =
    ColumnarBenchmarkSupport.executionOnly(state.execution, blackhole)

  @Benchmark
  def deepMaterialized(state: JoinAntiFitState, blackhole: Blackhole): Long =
    ColumnarBenchmarkSupport.deepMaterialized(state.execution, blackhole)

  @Benchmark
  def matchedConsumption(state: JoinAntiFitState): Long =
    ColumnarBenchmarkSupport.deepMaterializedSum(state.execution)
