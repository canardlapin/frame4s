package frame4s.benchmarks

import frame4s.*
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.profile.GCProfiler
import org.openjdk.jmh.results.RunResult
import org.openjdk.jmh.results.format.ResultFormatType
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.{OptionsBuilder, TimeValue}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Records pre-optimization behavior across sorted, shuffled, and adversarial fallback fixtures. */
object JoinAntiFitCourtRunner:
  final private case class Configuration(
      receipt: Path,
      sizes: Vector[Int],
      workloads: Vector[String],
      heap: String,
      quick: Boolean
  )

  final private case class Metric(
      rows: Int,
      workload: String,
      endpoint: String,
      milliseconds: Double,
      allocation: Double
  )

  final private case class Validation(
      rows: Int,
      workload: String,
      outputRows: Long,
      checksum: String,
      strategy: String,
      coldDetectMilliseconds: Double,
      warmDetectMilliseconds: Double,
      leftDigest: String,
      rightDigest: String
  )

  final private case class StageSample(
      rows: Int,
      workload: String,
      temperature: String,
      sample: Int,
      stage: String,
      milliseconds: Double
  )

  private val Endpoints = Map(
    "gatherView" -> "gather-view",
    "deepMaterialized" -> "deep-materialized",
    "matchedConsumption" -> "matched-consumption"
  )

  def main(arguments: Array[String]): Unit =
    val configuration = parse(arguments.toList)
    Files.createDirectories(configuration.receipt.resolve("raw"))
    val raw = configuration.receipt.resolve("raw/jmh.json")
    val log = configuration.receipt.resolve("raw/jmh.log")
    val builder = new OptionsBuilder()
      .include(
        "frame4s\\.benchmarks\\.JoinAntiFitBenchmarks\\." +
          "(gatherView|deepMaterialized|matchedConsumption)"
      )
      .param("rows", configuration.sizes.map(_.toString)*)
      .param("workload", configuration.workloads*)
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

    val profiled = profileConfigurations(configuration)
    val results = new Runner(builder.build()).run().asScala.toVector
    sanitizeMachinePaths(raw, configuration.receipt)
    sanitizeMachinePaths(log, configuration.receipt)
    val metrics = extractMetrics(results)
    requireComplete(configuration, metrics)
    writeEnvironment(configuration)
    writeMetrics(configuration.receipt, metrics)
    writeValidations(configuration.receipt, profiled._1)
    writeStages(configuration.receipt, profiled._2)
    writeSummary(configuration, metrics, profiled._1)

  private def parse(arguments: List[String]): Configuration =
    def value(flag: String): Option[String] =
      arguments.sliding(2).collectFirst { case List(`flag`, actual) => actual }

    def commaSeparated(flag: String, default: String): Vector[String] =
      value(flag)
        .getOrElse(default)
        .split(',')
        .iterator
        .map(_.trim)
        .filter(_.nonEmpty)
        .toVector

    val sizes =
      commaSeparated("--sizes", "1000,64000,1000000").map(_.toInt)
    if sizes.isEmpty || sizes.exists(_ <= 0) then
      throw new IllegalArgumentException("--sizes must contain positive integers")
    val workloads =
      commaSeparated("--workloads", JoinAntiFitFixture.Workloads.mkString(","))
    val invalid = workloads.filterNot(JoinAntiFitFixture.Workloads.contains)
    if invalid.nonEmpty then
      throw new IllegalArgumentException(
        s"unsupported --workloads: ${invalid.mkString(", ")}"
      )

    Configuration(
      receipt = Path.of:
        value("--receipt").getOrElse:
          throw new IllegalArgumentException("--receipt <directory> is required")
      ,
      sizes = sizes.distinct,
      workloads = workloads.distinct,
      heap = value("--heap").getOrElse("4g"),
      quick = arguments.contains("--quick")
    )

  private def profileConfigurations(
      configuration: Configuration
  ): (Vector[Validation], Vector[StageSample]) =
    val validations = Vector.newBuilder[Validation]
    val stages = Vector.newBuilder[StageSample]
    val warmSamples = if configuration.quick then 1 else 3

    configuration.sizes.foreach: rows =>
      configuration.workloads.foreach: workload =>
        val state = new JoinAntiFitState
        state.rows = rows
        state.workload = workload
        state.setup()
        try
          val cold = state.execution.profileRun()
          val coldResult = validateRun(state, cold.run)
          val coldStrategy = strategy(cold.stages)
          cold.stages.foreach: timing =>
            stages += StageSample(
              rows,
              workload,
              "cold",
              1,
              timing.stage,
              timing.nanoseconds.toDouble / 1e6
            )
          coldResult.close()

          var warmup = 0
          while warmup < 2 do
            val run = state.execution.run()
            val result = validateRun(state, run)
            result.close()
            warmup += 1

          val warmProfiles = Vector.newBuilder[ColumnarProfileRun]
          var sample = 1
          while sample <= warmSamples do
            val profiled = state.execution.profileRun()
            val result = validateRun(state, profiled.run)
            try
              if sample == 1 then
                val batches = result.recordBatches.fold(
                  reason => throw new IllegalStateException(reason),
                  identity
                )
                try
                  val deepChecksum = checksumBatches(batches)
                  if deepChecksum != state.expectedChecksum then
                    throw new IllegalStateException(
                      s"$rows/$workload deep checksum " +
                        s"${java.lang.Long.toUnsignedString(deepChecksum)} != " +
                        s"${java.lang.Long.toUnsignedString(state.expectedChecksum)}"
                    )
                finally batches.foreach(_.close())
            finally result.close()
            profiled.stages.foreach: timing =>
              stages += StageSample(
                rows,
                workload,
                "warm",
                sample,
                timing.stage,
                timing.nanoseconds.toDouble / 1e6
              )
            warmProfiles += profiled
            sample += 1

          val warm = warmProfiles.result()
          val warmStrategies = warm.map(value => strategy(value.stages)).distinct
          if warmStrategies != Vector(coldStrategy) then
            throw new IllegalStateException(
              s"$rows/$workload changed strategy: cold=$coldStrategy warm=$warmStrategies"
            )
          val expected = expectedStrategy(rows, workload)
          if coldStrategy != expected then
            throw new IllegalStateException(
              s"$rows/$workload used $coldStrategy; expected $expected"
            )
          validations += Validation(
            rows,
            workload,
            state.expectedRows,
            java.lang.Long.toUnsignedString(state.expectedChecksum),
            coldStrategy,
            stageMilliseconds(cold.stages, "detect-sorted").getOrElse(0.0),
            median(
              warm.flatMap(value => stageMilliseconds(value.stages, "detect-sorted"))
            ),
            state.leftDigest,
            state.rightDigest
          )
        finally state.tearDown()
    (validations.result(), stages.result())

  private def validateRun(
      state: JoinAntiFitState,
      run: ColumnarRun
  ): ColumnarResult =
    if run.receipt.fallback.nonEmpty then
      throw new IllegalStateException(
        s"${state.rows}/${state.workload} unexpectedly fell back to the reference interpreter"
      )
    val result = run.result.fold(
      error => throw new IllegalStateException(error.message),
      identity
    )
    val checksum = result.checksum.fold(
      error => throw new IllegalStateException(error.message),
      identity
    )
    if result.rowCount != state.expectedRows || checksum != state.expectedChecksum then
      result.close()
      throw new IllegalStateException(
        s"${state.rows}/${state.workload} produced ${result.rowCount}/" +
          s"${java.lang.Long.toUnsignedString(checksum)}; expected ${state.expectedRows}/" +
          s"${java.lang.Long.toUnsignedString(state.expectedChecksum)}"
      )
    result

  private def strategy(stages: Vector[KernelStageTiming]): String =
    if stages.exists(_.stage == "merge-probe") then "sorted-merge"
    else if stages.exists(_.stage == "build") then "hash"
    else throw new IllegalStateException("join profile contains no merge or hash stage")

  private def expectedStrategy(rows: Int, workload: String): String =
    val sortedCandidate =
      !Set("nullable-key", "fully-shuffled", "late-inversion").contains(workload)
    val rightRows = JoinAntiFitFixture.rightRows(rows, workload)
    if sortedCandidate && rows >= 16384 && rightRows >= 16384 then "sorted-merge"
    else "hash"

  private def stageMilliseconds(
      stages: Vector[KernelStageTiming],
      name: String
  ): Option[Double] =
    stages.find(_.stage == name).map(_.nanoseconds.toDouble / 1e6)

  private def checksumBatches(batches: Vector[RecordBatch]): Long =
    var hash = batches.foldLeft(0L)(_ + _.rowCount.toLong)
    var batchIndex = 0
    while batchIndex < batches.length do
      val batch = batches(batchIndex)
      var row = 0
      while row < batch.rowCount do
        var column = 0
        while column < batch.columns.length do
          val scalar = batch
            .columns(column)
            .scalar(row)
            .fold(
              error => throw new IllegalStateException(error.message),
              identity
            )
          hash = hash * 31L + BenchmarkSupport.scalarHash(scalar)
          column += 1
        row += 1
      batchIndex += 1
    hash

  private def extractMetrics(results: Vector[RunResult]): Vector[Metric] =
    results.map: result =>
      val method = result.getParams.getBenchmark.split('.').last
      val endpoint = Endpoints.getOrElse(
        method,
        throw new IllegalStateException(s"unexpected anti-fit endpoint $method")
      )
      val allocation = result.getSecondaryResults.asScala
        .get("gc.alloc.rate.norm")
        .map(_.getScore)
        .getOrElse:
          throw new IllegalStateException(s"$method has no normalized allocation")
      Metric(
        result.getParams.getParam("rows").toInt,
        result.getParams.getParam("workload"),
        endpoint,
        result.getPrimaryResult.getScore,
        allocation
      )

  private def requireComplete(
      configuration: Configuration,
      metrics: Vector[Metric]
  ): Unit =
    val expected =
      configuration.sizes
        .flatMap: rows =>
          configuration.workloads.flatMap: workload =>
            Endpoints.values.map(endpoint => (rows, workload, endpoint))
        .toSet
    val actual =
      metrics.map(metric => (metric.rows, metric.workload, metric.endpoint)).toSet
    val missing = expected -- actual
    if missing.nonEmpty then
      throw new IllegalStateException(
        s"anti-fit court omitted ${missing.toVector.sorted.mkString(", ")}"
      )

  private def writeEnvironment(configuration: Configuration): Unit =
    val runtime = Runtime.getRuntime
    val properties = Vector(
      "receipt_format=1",
      "suite=frame4s-join-antifit-court",
      s"quick=${configuration.quick}",
      s"sizes=${configuration.sizes.mkString(",")}",
      s"workloads=${configuration.workloads.mkString(",")}",
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
      "oracle=closed-form-workload-specific-match-map",
      "order_contract=stable-left-then-stable-right",
      "endpoints=gather-view,deep-materialized,matched-consumption"
    )
    write(
      configuration.receipt.resolve("environment.properties"),
      properties.mkString("", "\n", "\n")
    )

  private def writeMetrics(receipt: Path, metrics: Vector[Metric]): Unit =
    val rows = metrics
      .sortBy(metric => (metric.rows, metric.workload, metric.endpoint))
      .map: metric =>
        f"${metric.rows}\t${metric.workload}\t${metric.endpoint}\t" +
          f"${metric.milliseconds}%.9f\t${metric.allocation}%.3f"
    write(
      receipt.resolve("metrics.tsv"),
      (
        "rows\tworkload\tendpoint\tmilliseconds\tallocation_bytes" +: rows
      ).mkString("", "\n", "\n")
    )

  private def writeValidations(
      receipt: Path,
      validations: Vector[Validation]
  ): Unit =
    val rows = validations
      .sortBy(value => (value.rows, value.workload))
      .map: value =>
        f"${value.rows}\t${value.workload}\t${value.outputRows}\t${value.checksum}\t" +
          f"${value.strategy}\t${value.coldDetectMilliseconds}%.6f\t" +
          f"${value.warmDetectMilliseconds}%.6f\t${value.leftDigest}\t${value.rightDigest}"
    write(
      receipt.resolve("validation.tsv"),
      (
        (
          "rows\tworkload\toutput_rows\tchecksum\tstrategy\tcold_detect_ms\t" +
            "warm_detect_ms\tleft_keys_sha256\tright_keys_sha256"
        ) +: rows
      ).mkString("", "\n", "\n")
    )

  private def writeStages(receipt: Path, samples: Vector[StageSample]): Unit =
    val rows = samples.map: value =>
      f"${value.rows}\t${value.workload}\t${value.temperature}\t${value.sample}\t" +
        f"${value.stage}\t${value.milliseconds}%.6f"
    write(
      receipt.resolve("raw/stages.tsv"),
      (
        "rows\tworkload\ttemperature\tsample\tstage\tmilliseconds" +: rows
      ).mkString("", "\n", "\n")
    )
    val summary = samples
      .groupBy(value => (value.rows, value.workload, value.temperature, value.stage))
      .toVector
      .sortBy(_._1)
      .map: (key, values) =>
        f"${key._1}\t${key._2}\t${key._3}\t${key._4}\t" +
          f"${median(values.map(_.milliseconds))}%.6f"
    write(
      receipt.resolve("stage-summary.tsv"),
      (
        "rows\tworkload\ttemperature\tstage\tmedian_milliseconds" +: summary
      ).mkString("", "\n", "\n")
    )

  private def writeSummary(
      configuration: Configuration,
      metrics: Vector[Metric],
      validations: Vector[Validation]
  ): Unit =
    val validationByKey =
      validations.map(value => (value.rows, value.workload) -> value).toMap
    val rows = metrics
      .sortBy(metric => (metric.rows, metric.workload, metric.endpoint))
      .map: metric =>
        val validation = validationByKey(metric.rows -> metric.workload)
        f"| ${metric.rows} | ${metric.workload} | ${validation.strategy} | " +
          f"${metric.endpoint} | ${metric.milliseconds}%.3f | " +
          f"${metric.allocation / 1000000.0}%.2f |"
    val mode =
      if configuration.quick then "Quick provisional receipt." else "Full receipt."
    val summary =
      s"""@# Join anti-fitting regime court
          @
          @$mode Every result is checked against a workload-specific analytic oracle.
          @Strategy is derived from executed profile stages, not inferred from key order.
          @
          @| Rows | Workload | Strategy | Endpoint | ms/op | Allocation MB/op |
          @|---:|---|---|---|---:|---:|
          @${rows.mkString("\n")}
          @
          @`validation.tsv` records exact output rows and checksums, actual dispatch,
          @cold/warm sorted-detection time, and input fingerprints. `stage-summary.tsv`
          @retains all executed stages.
          @""".stripMargin('@')
    write(configuration.receipt.resolve("summary.md"), summary)

  private def median(values: Vector[Double]): Double =
    if values.isEmpty then 0.0
    else
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
