package frame4s.benchmarks

import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.profile.GCProfiler
import org.openjdk.jmh.results.RunResult
import org.openjdk.jmh.results.format.ResultFormatType
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.{OptionsBuilder, TimeValue}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Claim-gated one-shot join court.
  *
  * The ordinary `ColumnarBenchmarks` methods include a serial full-result checksum. Their
  * `ExecutionOnly` siblings stop after detached result construction. Both travel together so a
  * comparator ratio can select construction-only work while validation remains explicit.
  */
object JoinPerformanceCourtRunner:
  final private case class Configuration(
      receipt: Path,
      baseline: Option[Path],
      quick: Boolean,
      rows: Int,
      heap: String
  )

  final private case class Validation(
      workload: String,
      outputRows: Long,
      checksum: String
  )

  final private case class Metric(
      workload: String,
      path: String,
      milliseconds: Double,
      allocation: Double
  )

  final private case class StageSample(
      sample: Int,
      stage: String,
      milliseconds: Double
  )

  private val Workloads = Vector(
    "joinOneToOne",
    "joinOneToMany",
    "joinSparse",
    "joinSkewed",
    "semiJoinSparse",
    "antiJoinSparse"
  )
  private val MinimumParallelSpeedup = 1.15
  private val MaximumOneToOneAllocation = 85_000_000.0
  private val MaximumRepresentativeAllocationRegression = 1.05

  def main(arguments: Array[String]): Unit =
    val configuration = parse(arguments.toList)
    Files.createDirectories(configuration.receipt.resolve("raw"))
    val raw = configuration.receipt.resolve("raw/jmh.json")
    val log = configuration.receipt.resolve("raw/jmh.log")
    val methods = Workloads
      .flatMap(workload => Vector(workload, s"${workload}ExecutionOnly"))
      .mkString("(", "|", ")")
    val builder = new OptionsBuilder()
      .include(s"frame4s\\.benchmarks\\.ColumnarBenchmarks\\.$methods")
      .param("rows", configuration.rows.toString)
      .mode(Mode.AverageTime)
      .result(raw.toString)
      .resultFormat(ResultFormatType.JSON)
      .output(log.toString)
      .addProfiler(classOf[GCProfiler])
      .jvmArgsAppend(
        s"-Xms${configuration.heap}",
        s"-Xmx${configuration.heap}",
        "-XX:+AlwaysPreTouch"
      )

    if configuration.quick then
      val _ = builder
        .warmupIterations(1)
        .warmupTime(TimeValue.milliseconds(100))
        .measurementIterations(1)
        .measurementTime(TimeValue.milliseconds(150))
        .forks(1)

    val validations = validate(configuration.rows)
    val stages = profileStages(configuration.rows, if configuration.quick then 3 else 9)
    val results = new Runner(builder.build()).run().asScala.toVector
    sanitizeMachinePaths(raw, configuration.receipt)
    sanitizeMachinePaths(log, configuration.receipt)
    val metrics = extractMetrics(results)
    requireComplete(metrics)
    writeEnvironment(configuration)
    writeValidations(configuration.receipt, validations)
    writeStageSamples(configuration.receipt, stages)
    writeMetrics(configuration.receipt, metrics)
    writeSummary(configuration, metrics, stages)

  private def parse(arguments: List[String]): Configuration =
    def value(flag: String): Option[String] =
      arguments.sliding(2).collectFirst { case List(`flag`, actual) => actual }

    val rows = value("--rows").fold(1_000_000)(_.toInt)
    if rows <= 0 then throw new IllegalArgumentException("--rows must be positive")
    Configuration(
      receipt = Path.of:
        value("--receipt").getOrElse:
          throw new IllegalArgumentException("--receipt <directory> is required")
      ,
      baseline = value("--baseline").map(Path.of(_)),
      quick = arguments.contains("--quick"),
      rows = rows,
      heap = value("--heap").getOrElse("8g")
    )

  private def validate(rows: Int): Vector[Validation] =
    val state = new ReferenceState
    state.rows = rows
    state.setup()
    val benchmark = new ColumnarBenchmarks
    try
      Vector(
        validation("joinOneToOne", rows, benchmark.joinOneToOne(state)),
        validation("joinOneToMany", rows, benchmark.joinOneToMany(state)),
        validation("joinSparse", (rows + 9L) / 10L, benchmark.joinSparse(state)),
        validation("joinSkewed", math.min(rows, 1000), benchmark.joinSkewed(state)),
        validation("semiJoinSparse", (rows + 9L) / 10L, benchmark.semiJoinSparse(state)),
        validation(
          "antiJoinSparse",
          rows - (rows + 9L) / 10L,
          benchmark.antiJoinSparse(state)
        )
      )
    finally state.tearDown()

  private def validation(workload: String, rows: Long, checksum: Long): Validation =
    Validation(workload, rows, java.lang.Long.toUnsignedString(checksum))

  private def profileStages(rows: Int, samples: Int): Vector[StageSample] =
    val state = new ReferenceState
    state.rows = rows
    state.setup()
    try
      var warmup = 0
      while warmup < 10 do
        val profiled = state.columnarJoinOne.profileRun()
        val result =
          profiled.run.result
            .fold(error => throw new IllegalStateException(error.message), identity)
        result.close()
        warmup += 1

      val output = Vector.newBuilder[StageSample]
      var sample = 1
      var expectedChecksum: Option[Long] = None
      while sample <= samples do
        val profiled = state.columnarJoinOne.profileRun()
        if profiled.run.receipt.fallback.nonEmpty then
          throw new IllegalStateException("profiled join unexpectedly fell back")
        val result =
          profiled.run.result
            .fold(error => throw new IllegalStateException(error.message), identity)
        try
          profiled.stages.foreach: timing =>
            output += StageSample(sample, timing.stage, timing.nanoseconds.toDouble / 1e6)
          val checksumStarted = System.nanoTime()
          val checksum =
            result.checksum.fold(error => throw new IllegalStateException(error.message), identity)
          val checksumNanos = System.nanoTime() - checksumStarted
          expectedChecksum match
            case Some(expected) if expected != checksum =>
              throw new IllegalStateException("profiled join checksum is nondeterministic")
            case None => expectedChecksum = Some(checksum)
            case _    => ()
          output += StageSample(sample, "checksum", checksumNanos.toDouble / 1e6)
        finally result.close()
        sample += 1
      output.result()
    finally state.tearDown()

  private def extractMetrics(results: Vector[RunResult]): Vector[Metric] =
    results.map: result =>
      val name = result.getParams.getBenchmark.split('.').last
      val executionOnly = name.endsWith("ExecutionOnly")
      val workload = if executionOnly then name.stripSuffix("ExecutionOnly") else name
      val allocation = result.getSecondaryResults.asScala
        .get("gc.alloc.rate.norm")
        .map(_.getScore)
        .getOrElse:
          throw new IllegalStateException(s"$name has no normalized allocation")
      Metric(
        workload,
        if executionOnly then "execution-only" else "consumed-checksum",
        result.getPrimaryResult.getScore,
        allocation
      )

  private def requireComplete(metrics: Vector[Metric]): Unit =
    val actual = metrics.map(metric => metric.workload -> metric.path).toSet
    val expected = Workloads
      .flatMap(workload => Vector(workload -> "execution-only", workload -> "consumed-checksum"))
      .toSet
    val missing = expected -- actual
    if missing.nonEmpty then
      throw new IllegalStateException(
        s"join court omitted ${missing.toVector.sorted.mkString(", ")}"
      )

  private def writeEnvironment(configuration: Configuration): Unit =
    val runtime = Runtime.getRuntime
    val properties = Vector(
      "receipt_format=1",
      "suite=frame4s-one-shot-join-performance-court",
      s"quick=${configuration.quick}",
      s"rows=${configuration.rows}",
      s"baseline=${configuration.baseline.fold("none")(_.getFileName.toString)}",
      s"java.version=${System.getProperty("java.version")}",
      s"java.vendor=${System.getProperty("java.vendor")}",
      s"os.name=${System.getProperty("os.name")}",
      s"os.version=${System.getProperty("os.version")}",
      s"os.arch=${System.getProperty("os.arch")}",
      s"processors=${runtime.availableProcessors()}",
      s"hardware=${sys.env.getOrElse("FRAME4S_HARDWARE", "unrecorded")}",
      s"benchmark.heap=-Xms${configuration.heap},-Xmx${configuration.heap}",
      "benchmark.mode=average-time",
      "benchmark.profiler=gc",
      "execution-only=detached-result-construction-with-jmh-blackhole",
      "consumed=execution-plus-serial-full-result-checksum",
      s"gate.parallel.minimum.speedup=$MinimumParallelSpeedup",
      s"gate.one-to-one.maximum.allocation.bytes=$MaximumOneToOneAllocation",
      s"gate.representative.maximum.allocation.ratio=$MaximumRepresentativeAllocationRegression"
    )
    write(
      configuration.receipt.resolve("environment.properties"),
      properties.mkString("", "\n", "\n")
    )

  private def writeValidations(receipt: Path, validations: Vector[Validation]): Unit =
    val rows = validations.map: value =>
      s"${value.workload}\t${value.outputRows}\t${value.checksum}\tcandidate self-consistency; small-tier oracle laws remain binding"
    write(
      receipt.resolve("validation.tsv"),
      (
        "workload\toutput_rows\tchecksum\tstatus" +: rows
      ).mkString("", "\n", "\n")
    )

  private def writeStageSamples(receipt: Path, samples: Vector[StageSample]): Unit =
    val rows = samples.map: sample =>
      f"${sample.sample}\t${sample.stage}\t${sample.milliseconds}%.6f"
    write(
      receipt.resolve("raw/stages.tsv"),
      ("sample\tstage\tmilliseconds" +: rows).mkString("", "\n", "\n")
    )
    val totals = samples.groupMapReduce(_.sample)(_.milliseconds)(_ + _)
    val summary = samples
      .groupBy(_.stage)
      .toVector
      .sortBy(_._1)
      .map: (stage, values) =>
        val milliseconds = median(values.map(_.milliseconds))
        val fractions = values.map(value => value.milliseconds / totals(value.sample))
        f"$stage\t$milliseconds%.6f\t${median(fractions) * 100.0}%.3f"
    write(
      receipt.resolve("stage-summary.tsv"),
      ("stage\tmedian_milliseconds\tmedian_fraction_percent" +: summary)
        .mkString("", "\n", "\n")
    )

  private def writeMetrics(receipt: Path, metrics: Vector[Metric]): Unit =
    val rows = metrics
      .sortBy(metric => metric.workload -> metric.path)
      .map: metric =>
        f"${metric.workload}\t${metric.path}\t${metric.milliseconds}%.9f\t${metric.allocation}%.3f"
    write(
      receipt.resolve("metrics.tsv"),
      ("workload\tpath\tmilliseconds\tallocation_bytes" +: rows)
        .mkString("", "\n", "\n")
    )

  private def writeSummary(
      configuration: Configuration,
      metrics: Vector[Metric],
      stages: Vector[StageSample]
  ): Unit =
    val byKey = metrics.map(metric => (metric.workload, metric.path) -> metric).toMap
    val baseline = configuration.baseline.map(readMetrics)
    var allocationPasses = true
    var parallelPasses = true
    val rows = Workloads.map: workload =>
      val execution = byKey(workload -> "execution-only")
      val consumed = byKey(workload -> "consumed-checksum")
      val checksumShare =
        math.max(0.0, consumed.milliseconds - execution.milliseconds) / consumed.milliseconds
      val comparison = baseline.flatMap(_.get(workload -> "execution-only"))
      val speedup = comparison.map(_.milliseconds / execution.milliseconds)
      val allocationRatio = comparison.map(base => execution.allocation / base.allocation)
      allocationPasses &&=
        execution.allocation <=
          comparison.fold(Double.PositiveInfinity)(
            _.allocation * MaximumRepresentativeAllocationRegression
          )
      if workload == "joinOneToOne" then
        allocationPasses &&= execution.allocation <= MaximumOneToOneAllocation
        parallelPasses &&= speedup.forall(_ >= MinimumParallelSpeedup)
      f"| $workload | ${execution.milliseconds}%.6f | ${consumed.milliseconds}%.6f | ${checksumShare * 100.0}%.1f%% | ${execution.allocation}%.0f | ${speedup.fold(
          "baseline"
        )(value => f"$value%.2fx")} | ${allocationRatio.fold("baseline")(value => f"$value%.3f")} |"

    val stageRows = stages
      .groupBy(_.stage)
      .toVector
      .sortBy(_._1)
      .map: (stage, values) =>
        f"| $stage | ${median(values.map(_.milliseconds))}%.6f |"
    val decision = configuration.baseline match
      case None =>
        "This is the same-harness baseline; no optimization is admitted from this receipt."
      case Some(_) =>
        val speed =
          if parallelPasses then "the candidate passes its >=1.15x one-to-one speed gate"
          else "the candidate misses its >=1.15x one-to-one speed gate"
        val allocation =
          if allocationPasses then "allocation passes its absolute and no-regression gates"
          else "allocation fails at least one absolute or representative no-regression gate"
        s"Decision: $speed; $allocation. Whether a parallel probe is admitted is stated by the candidate receipt that enables it."
    val mode = if configuration.quick then "Quick provisional receipt." else "Full receipt."
    val summary =
      s"""@# frame4s one-shot join performance court
         @
         @$mode $decision
         @
         @`execution-only` constructs and blackholes the detached result, then closes it.
         @`consumed-checksum` additionally walks every output cell in a serial checksum.
         @Comparator ratios must use the execution-only row; the consumed row remains a
         @validation and consumption-cost court.
         @
         @| Workload | Execution-only ms/op | Consumed ms/op | Approx. checksum share | Execution allocation B/op | Same-harness speedup | Allocation ratio |
         @|---|---:|---:|---:|---:|---:|---:|
         @${rows.mkString("\n")}
         @
         @## One-to-one stage attribution
         @
         @| Stage | Median ms |
         @|---|---:|
         @${stageRows.mkString("\n")}
         @
         @Individual stage samples are in `raw/stages.tsv`; `stage-summary.tsv`
         @records median time and median fraction. `metrics.tsv` is the stable input
         @for a later same-harness candidate receipt. Exact candidate checksums and
         @cardinalities are in `validation.tsv`; semantic oracle agreement remains
         @ratified by the small cross-platform court.
         @""".stripMargin('@')
    write(configuration.receipt.resolve("summary.md"), summary)

  private def readMetrics(directory: Path): Map[(String, String), Metric] =
    Files
      .readAllLines(directory.resolve("metrics.tsv"), StandardCharsets.UTF_8)
      .asScala
      .drop(1)
      .map: line =>
        val fields = line.split('\t')
        val metric = Metric(fields(0), fields(1), fields(2).toDouble, fields(3).toDouble)
        (metric.workload, metric.path) -> metric
      .toMap

  private def median(values: Vector[Double]): Double =
    val sorted = values.sorted
    if sorted.length % 2 == 1 then sorted(sorted.length / 2)
    else
      val upper = sorted.length / 2
      (sorted(upper - 1) + sorted(upper)) / 2.0

  private def write(path: Path, content: String): Unit =
    Files.writeString(path, content, StandardCharsets.UTF_8)
    ()

  private def sanitizeMachinePaths(path: Path, receipt: Path): Unit =
    val replacements = Vector(
      Some(receipt.toAbsolutePath.normalize.toString -> "<receipt>"),
      Option(System.getProperty("user.dir")).map(_ -> "<workspace>"),
      Option(System.getProperty("java.home")).map(_ -> "<java-home>"),
      Option(System.getProperty("user.home")).map(_ -> "<user-home>")
    ).flatten.sortBy((value, _) => -value.length)
    val sanitized = replacements.foldLeft(Files.readString(path, StandardCharsets.UTF_8)):
      case (content, (machinePath, label)) => content.replace(machinePath, label)
    write(path, sanitized)
