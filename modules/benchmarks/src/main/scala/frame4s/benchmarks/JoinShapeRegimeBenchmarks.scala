package frame4s.benchmarks

import frame4s.*
import org.openjdk.jmh.annotations.*
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

private[benchmarks] object JoinShapeRegimeFixture:
  val Workloads = Vector("join-sparse", "join-skewed", "semi-sparse", "anti-sparse")
  val Orders = Vector("sorted", "both-shuffled")

  def rightRows(rows: Int, workload: String): Int =
    workload match
      case "join-skewed" => math.max(1, math.min(rows, 1000))
      case _             => math.max(1, rows / 10)

  def rightKeys(rows: Int, workload: String, order: String): Array[Int] =
    val count = rightRows(rows, workload)
    workload match
      case "join-skewed" => Array.fill(count)(0)
      case _             =>
        val ordinals =
          if order == "sorted" then Array.tabulate(count)(identity)
          else JoinRegimeFixture.permutation(count, JoinRegimeFixture.RightSeed)
        ordinals.map(_ * 10)

  def rightValues(rows: Int, workload: String, order: String): Array[Int] =
    val count = rightRows(rows, workload)
    workload match
      case "join-skewed" =>
        if order == "sorted" then Array.tabulate(count)(identity)
        else JoinRegimeFixture.permutation(count, JoinRegimeFixture.RightSeed)
      case _ => rightKeys(rows, workload, order).map(_ * 2)

@State(org.openjdk.jmh.annotations.Scope.Benchmark)
class JoinShapeRegimeState:
  import BenchmarkSupport.*

  type Left = (key: Int, leftValue: Long)
  type Right = (rightKey: Int, rightValue: Long)
  type Joined = (key: Int, leftValue: Long, rightKey: Int, rightValue: Long)

  @Param(Array("1000000"))
  var rows: Int = 0

  @Param(Array("join-sparse"))
  var workload: String = ""

  @Param(Array("sorted"))
  var order: String = ""

  private var leftTable: Table[Left] = scala.compiletime.uninitialized
  private var rightTable: Table[Right] = scala.compiletime.uninitialized
  private[benchmarks] var execution: ColumnarExecution =
    scala.compiletime.uninitialized
  private[benchmarks] var expectedChecksum: Long = 0L
  private[benchmarks] var expectedRows: Long = 0L
  private[benchmarks] var leftDigest: String = ""
  private[benchmarks] var rightDigest: String = ""
  private[benchmarks] var rightValueDigest: String = ""

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
                storage(ColumnArray.int64(keys.map(_.toLong * 3L)))
              )
            )
        )
      )

  private def rightTableFrom(keys: Array[Int], values: Array[Int]): Table[Right] =
    val schema = summon[SchemaDescriptor[Right]].schema
    storage:
      Table[Right](
        Vector(
          storage:
            RecordBatch(
              schema,
              Vector(
                storage(ColumnArray.int32(keys)),
                storage(ColumnArray.int64(values.map(_.toLong)))
              )
            )
        )
      )

  @Setup(Level.Trial)
  def setup(): Unit =
    if rows <= 0 then throw new IllegalArgumentException("rows must be positive")
    if !JoinShapeRegimeFixture.Workloads.contains(workload) then
      throw new IllegalArgumentException(s"unsupported join workload: $workload")
    if !JoinShapeRegimeFixture.Orders.contains(order) then
      throw new IllegalArgumentException(s"unsupported join key order: $order")

    val leftKeys =
      if order == "sorted" then Array.tabulate(rows)(identity)
      else JoinRegimeFixture.permutation(rows, JoinRegimeFixture.LeftSeed)
    val rightKeys = JoinShapeRegimeFixture.rightKeys(rows, workload, order)
    val rightValues = JoinShapeRegimeFixture.rightValues(rows, workload, order)
    leftDigest = JoinRegimeFixture.digest(leftKeys)
    rightDigest = JoinRegimeFixture.digest(rightKeys)
    rightValueDigest = JoinRegimeFixture.digest(rightValues)
    leftTable = leftTableFrom(leftKeys)
    rightTable = rightTableFrom(rightKeys, rightValues)

    val leftRef = valuesRef(s"join-shape-left-$rows-$workload-$order")
    val rightRef = valuesRef(s"join-shape-right-$rows-$workload-$order")
    val left = frame(Frame.values[Left](leftRef))
    val right = frame(Frame.values[Right](rightRef))
    val query =
      workload match
        case "join-sparse" | "join-skewed" =>
          left.innerJoin(right)((lhs, rhs) => lhs.col("key") === rhs.col("rightKey"))
        case "semi-sparse" =>
          left.semiJoin(right)((lhs, rhs) => lhs.col("key") === rhs.col("rightKey"))
        case "anti-sparse" =>
          left.antiJoin(right)((lhs, rhs) => lhs.col("key") === rhs.col("rightKey"))
    val sources =
      ReferenceSources.empty.bind(leftRef, leftTable).bind(rightRef, rightTable)
    execution = ColumnarInterpreter.prepare(query.plan, sources)
    val expected = expectedResult(leftKeys, rightValues)
    expectedRows = expected._1
    expectedChecksum = expected._2

  private def expectedResult(
      leftKeys: Array[Int],
      rightValues: Array[Int]
  ): (Long, Long) =
    val rightSparseRows = math.max(1, rows / 10)
    val outputRows =
      workload match
        case "join-skewed"                 => rightValues.length.toLong
        case "join-sparse" | "semi-sparse" => rightSparseRows.toLong
        case "anti-sparse"                 => rows.toLong - rightSparseRows.toLong
    var hash = outputRows
    var index = 0
    while index < leftKeys.length do
      val key = leftKeys(index)
      val sparseMatch = key % 10 == 0 && key / 10 < rightSparseRows
      workload match
        case "join-sparse" if sparseMatch =>
          hash = hash * 31L + key.toLong
          hash = hash * 31L + key.toLong * 3L
          hash = hash * 31L + key.toLong
          hash = hash * 31L + key.toLong * 2L
        case "join-skewed" if key == 0 =>
          var right = 0
          while right < rightValues.length do
            hash = hash * 31L
            hash = hash * 31L
            hash = hash * 31L
            hash = hash * 31L + rightValues(right).toLong
            right += 1
        case "semi-sparse" if sparseMatch =>
          hash = hash * 31L + key.toLong
          hash = hash * 31L + key.toLong * 3L
        case "anti-sparse" if !sparseMatch =>
          hash = hash * 31L + key.toLong
          hash = hash * 31L + key.toLong * 3L
        case _ => ()
      index += 1
    outputRows -> hash

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
class JoinShapeRegimeBenchmarks:
  @Benchmark
  def joinExecutionOnly(state: JoinShapeRegimeState, blackhole: Blackhole): Long =
    ColumnarBenchmarkSupport.executionOnly(state.execution, blackhole)
