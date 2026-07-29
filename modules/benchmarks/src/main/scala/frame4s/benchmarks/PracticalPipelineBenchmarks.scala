package frame4s.benchmarks

import frame4s.*
import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

private[benchmarks] object PracticalPipelineBenchmarkSupport:
  def referenceRows[S <: scala.NamedTuple.AnyNamedTuple](
      frame: Frame[S],
      sources: ReferenceSources
  )(using SchemaDescriptor[S]): Long =
    val table = BenchmarkSupport.collect(frame, sources)
    try table.rowCount
    finally table.close()

  def columnarRows(execution: ColumnarExecution): Long =
    val run = execution.run()
    if run.receipt.fallback.nonEmpty then
      throw new IllegalStateException(
        s"benchmark unexpectedly fell back: ${run.receipt.fallback.getOrElse("unknown")}"
      )
    val result = run.result.fold(error => throw new IllegalStateException(error.message), identity)
    try result.rowCount
    finally result.close()

@State(org.openjdk.jmh.annotations.Scope.Benchmark)
class PracticalPipelineState:
  import BenchmarkSupport.*

  type People = (
      name: String,
      species: Option[String],
      sex: Option[String],
      skin: String,
      eyes: String,
      height: Option[Double],
      mass: Option[Double]
  )
  type Derived = (
      name: String,
      species: Option[String],
      sex: Option[String],
      heightM: Option[Double],
      bmi: Option[Double]
  )
  type Selected = (
      species: Option[String],
      sex: Option[String],
      height: Option[Double],
      mass: Option[Double]
  )
  type Grouped = (
      species: Option[String],
      sex: Option[String],
      height: Option[Double],
      mass: Option[Double]
  )

  @Param(Array("10000"))
  var rows: Int = 0

  var table: Table[People] = scala.compiletime.uninitialized
  var sources: ReferenceSources = ReferenceSources.empty
  var derived: Frame[Derived] = scala.compiletime.uninitialized
  var grouped: Frame[Grouped] = scala.compiletime.uninitialized
  private[benchmarks] var columnarDerived: ColumnarExecution =
    scala.compiletime.uninitialized
  private[benchmarks] var columnarGrouped: ColumnarExecution =
    scala.compiletime.uninitialized

  @Setup(Level.Trial)
  def setup(): Unit =
    val reference = valueRef("practical-people")
    val fixture = Vector.tabulate(rows): index =>
      (
        name = f"person-$index%08d",
        species =
          if index % 17 == 0 then None
          else Some(f"species-${index % 16}%02d"),
        sex =
          if index % 19 == 0 then None
          else Some(if (index & 1) == 0 then "female" else "male"),
        skin = if index % 3 == 0 then "light" else "dark",
        eyes = if index % 5 == 0 then "brown" else "blue",
        height = Option.when(index % 23 != 0)(150.0 + (index % 60).toDouble),
        mass = Option.when(index % 29 != 0)(50.0 + (index % 100).toDouble / 2.0)
      )
    table = Table
      .fromRows[People](fixture, math.max(1, math.min(rows, 1024)))
      .fold(error => throw new IllegalStateException(error.toString), identity)
    val source = frame(Frame.values[People](reference))

    val withHeight = source
      .filter: row =>
        (row.col("skin") === "light") && (row.col("eyes") === "brown")
      .withColumn("heightM")(row => row.col("height") / Option(100.0))
    val withBmi = withHeight.withColumn("bmi"): row =>
      row.col("mass") / (row.col("heightM") * row.col("heightM"))
    derived = withBmi.select: row =>
      (
        row.col("name"),
        row.col("species"),
        row.col("sex"),
        row.col("heightM"),
        row.col("bmi")
      )

    val selected: Frame[Selected] = source.select: row =>
      (
        row.col("species"),
        row.col("sex"),
        row.col("height"),
        row.col("mass")
      )
    grouped = selected
      .groupBy(row => (row.col("species"), row.col("sex")))
      .aggregate: row =>
        (
          Aggregate.mean(row.col("height")).as("height"),
          Aggregate.mean(row.col("mass")).as("mass")
        )

    sources = ReferenceSources.empty.bind(reference, table)
    columnarDerived = ColumnarInterpreter.prepare(derived.plan, sources)
    columnarGrouped = ColumnarInterpreter.prepare(grouped.plan, sources)

  @TearDown(Level.Trial)
  def tearDown(): Unit =
    columnarDerived.close()
    columnarGrouped.close()
    table.close()

  private def valueRef(id: String): SourceRef =
    frame(SourceRef.values(id, id))

@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"))
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
class PracticalPipelineReferenceCourt:
  import PracticalPipelineBenchmarkSupport.*

  @Benchmark
  def filterWithColumnsSelect(state: PracticalPipelineState): Long =
    referenceRows(state.derived, state.sources)

  @Benchmark
  def selectGroupSummarise(state: PracticalPipelineState): Long =
    referenceRows(state.grouped, state.sources)

@BenchmarkMode(Array(Mode.AverageTime, Mode.Throughput))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = Array("-Xms1g", "-Xmx1g", "-XX:+AlwaysPreTouch"))
@Warmup(iterations = 3, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
class PracticalPipelineColumnarCourt:
  import PracticalPipelineBenchmarkSupport.*

  @Benchmark
  def filterWithColumnsSelect(state: PracticalPipelineState): Long =
    columnarRows(state.columnarDerived)

  @Benchmark
  def selectGroupSummarise(state: PracticalPipelineState): Long =
    columnarRows(state.columnarGrouped)
