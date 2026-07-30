package frame4s.benchmarks

import frame4s.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private[benchmarks] object JoinRegimeFixture:
  val Orders = Vector("sorted", "right-shuffled", "both-shuffled")
  val LeftSeed = 0x6a09e667f3bcc909L
  val RightSeed = 0xbb67ae8584caa73bL
  private val Gamma = 0x9e3779b97f4a7c15L

  def permutation(size: Int, seed: Long): Array[Int] =
    val values = Array.tabulate(size)(identity)
    var state = seed
    var index = size - 1
    while index > 0 do
      state += Gamma
      var mixed = state
      mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L
      mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL
      mixed ^= mixed >>> 31
      val selected =
        java.lang.Long.remainderUnsigned(mixed, index.toLong + 1L).toInt
      val swap = values(index)
      values(index) = values(selected)
      values(selected) = swap
      index -= 1
    values

  def orderedKeys(rows: Int, order: String, left: Boolean): Array[Int] =
    order match
      case "sorted"                 => Array.tabulate(rows)(identity)
      case "right-shuffled" if left => Array.tabulate(rows)(identity)
      case "right-shuffled"         => permutation(rows, RightSeed)
      case "both-shuffled"          =>
        permutation(rows, if left then LeftSeed else RightSeed)
      case value =>
        throw new IllegalArgumentException(s"unsupported join key order: $value")

  def digest(values: Array[Int]): String =
    val digest = MessageDigest.getInstance("SHA-256")
    var index = 0
    while index < values.length do
      val value = values(index)
      digest.update((value & 0xff).toByte)
      digest.update(((value >>> 8) & 0xff).toByte)
      digest.update(((value >>> 16) & 0xff).toByte)
      digest.update(((value >>> 24) & 0xff).toByte)
      index += 1
    digest.digest().map(byte => f"${byte & 0xff}%02x").mkString

@State(org.openjdk.jmh.annotations.Scope.Benchmark)
class JoinRegimeState:
  import BenchmarkSupport.*

  type Left = (key: Int, leftValue: Long)
  type Right = (rightKey: Int, rightValue: Long)
  type Joined = (key: Int, leftValue: Long, rightKey: Int, rightValue: Long)

  @Param(Array("1000"))
  var rows: Int = 0

  @Param(Array("sorted"))
  var order: String = ""

  private var leftTable: Table[Left] = scala.compiletime.uninitialized
  private var rightTable: Table[Right] = scala.compiletime.uninitialized
  private[benchmarks] var execution: ColumnarExecution =
    scala.compiletime.uninitialized
  private[benchmarks] var expectedChecksum: Long = 0L
  private[benchmarks] var leftDigest: String = ""
  private[benchmarks] var rightDigest: String = ""

  private def valuesRef(id: String): SourceRef =
    frame(SourceRef.values(id, id))

  private def leftTableFrom(keys: Array[Int]): Table[Left] =
    val schema = summon[SchemaDescriptor[Left]].schema
    storage:
      Table[Left](
        Vector(
          storage:
            RecordBatch(
              schema,
              Vector(
                storage(ColumnArray.int32(keys)),
                storage(ColumnArray.int64(keys.map(_.toLong)))
              )
            )
        )
      )

  private def rightTableFrom(keys: Array[Int]): Table[Right] =
    val schema = summon[SchemaDescriptor[Right]].schema
    storage:
      Table[Right](
        Vector(
          storage:
            RecordBatch(
              schema,
              Vector(
                storage(ColumnArray.int32(keys)),
                storage(ColumnArray.int64(keys.map(_.toLong * 2L)))
              )
            )
        )
      )

  @Setup(Level.Trial)
  def setup(): Unit =
    if rows <= 0 then throw new IllegalArgumentException("rows must be positive")
    if !JoinRegimeFixture.Orders.contains(order) then
      throw new IllegalArgumentException(s"unsupported join key order: $order")

    val leftKeys = JoinRegimeFixture.orderedKeys(rows, order, left = true)
    val rightKeys = JoinRegimeFixture.orderedKeys(rows, order, left = false)
    leftDigest = JoinRegimeFixture.digest(leftKeys)
    rightDigest = JoinRegimeFixture.digest(rightKeys)
    leftTable = leftTableFrom(leftKeys)
    rightTable = rightTableFrom(rightKeys)

    val leftRef = valuesRef(s"join-regime-left-$rows-$order")
    val rightRef = valuesRef(s"join-regime-right-$rows-$order")
    val left = frame(Frame.values[Left](leftRef))
    val right = frame(Frame.values[Right](rightRef))
    val joined: Frame[Joined] =
      left.innerJoin(right)((lhs, rhs) => lhs.col("key") === rhs.col("rightKey"))
    val sources =
      ReferenceSources.empty.bind(leftRef, leftTable).bind(rightRef, rightTable)
    execution = ColumnarInterpreter.prepare(joined.plan, sources)

    var hash = rows.toLong
    var index = 0
    while index < leftKeys.length do
      val key = leftKeys(index).toLong
      hash = hash * 31L + key
      hash = hash * 31L + key
      hash = hash * 31L + key
      hash = hash * 31L + key * 2L
      index += 1
    expectedChecksum = hash

  @TearDown(Level.Trial)
  def tearDown(): Unit =
    execution.close()
    leftTable.close()
    rightTable.close()

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
class JoinRegimeBenchmarks:
  @Benchmark
  def joinExecutionOnly(state: JoinRegimeState, blackhole: Blackhole): Long =
    ColumnarBenchmarkSupport.executionOnly(state.execution, blackhole)
