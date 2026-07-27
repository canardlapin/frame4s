package frame4s.benchmarks

import frame4s.{ColumnarExecution, ColumnarInterpreter}
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
class PreparedJoinState extends ReferenceState:
  var preparedJoinOne: ColumnarExecution = scala.compiletime.uninitialized
  var preparedJoinMany: ColumnarExecution = scala.compiletime.uninitialized
  var preparedJoinSparse: ColumnarExecution = scala.compiletime.uninitialized
  var preparedJoinSkew: ColumnarExecution = scala.compiletime.uninitialized

  @Setup(Level.Trial)
  override def setup(): Unit =
    super.setup()
    preparedJoinOne = prepareJoinOne()
    preparedJoinMany = prepareJoinMany()
    preparedJoinSparse = prepareJoinSparse()
    preparedJoinSkew = prepareJoinSkew()

  @TearDown(Level.Trial)
  override def tearDown(): Unit =
    preparedJoinOne.close()
    preparedJoinMany.close()
    preparedJoinSparse.close()
    preparedJoinSkew.close()
    super.tearDown()

  def prepareJoinOne(): ColumnarExecution =
    prepare(joinOne.plan)

  def prepareJoinMany(): ColumnarExecution =
    prepare(joinMany.plan)

  def prepareJoinSparse(): ColumnarExecution =
    prepare(joinSparse.plan)

  def prepareJoinSkew(): ColumnarExecution =
    prepare(joinSkew.plan)

  private def prepare(
      plan: frame4s.LogicalPlan
  ): ColumnarExecution =
    ColumnarInterpreter
      .prepareIndexed(plan, sources)
      .fold(error => throw new IllegalStateException(error.message), identity)

@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"))
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
class PreparedJoinCourt:
  import ColumnarBenchmarkSupport.checksum

  @Benchmark
  def joinOneToOne(state: PreparedJoinState): Long =
    checksum(state.preparedJoinOne)

  @Benchmark
  def joinOneToMany(state: PreparedJoinState): Long =
    checksum(state.preparedJoinMany)

  @Benchmark
  def joinSparse(state: PreparedJoinState): Long =
    checksum(state.preparedJoinSparse)

  @Benchmark
  def joinSkewed(state: PreparedJoinState): Long =
    checksum(state.preparedJoinSkew)

@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"))
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
class PreparedJoinBuildCourt:
  @Benchmark
  def joinOneToOne(state: PreparedJoinState): Int =
    prepareAndClose(state.prepareJoinOne())

  @Benchmark
  def joinOneToMany(state: PreparedJoinState): Int =
    prepareAndClose(state.prepareJoinMany())

  @Benchmark
  def joinSparse(state: PreparedJoinState): Int =
    prepareAndClose(state.prepareJoinSparse())

  @Benchmark
  def joinSkewed(state: PreparedJoinState): Int =
    prepareAndClose(state.prepareJoinSkew())

  private def prepareAndClose(execution: ColumnarExecution): Int =
    try execution.physicalExplain.hashCode
    finally execution.close()
