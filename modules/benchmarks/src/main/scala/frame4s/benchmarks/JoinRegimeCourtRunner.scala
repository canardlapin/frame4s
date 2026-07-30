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

/** Measures one-to-one join behavior across size, key order, and selected strategy. */
object JoinRegimeCourtRunner:
  final private case class Configuration(
      receipt: Path,
      sizes: Vector[Int],
      orders: Vector[String],
      heap: String,
      quick: Boolean
  )

  final private case class Metric(
      rows: Int,
      order: String,
      milliseconds: Double,
      allocation: Double
  )

  final private case class Validation(
      rows: Int,
      order: String,
      outputRows: Long,
      checksum: String,
      leftDigest: String,
      rightDigest: String
  )

  final private case class StageSample(
      rows: Int,
      order: String,
      sample: Int,
      stage: String,
      milliseconds: Double
  )

  def main(arguments: Array[String]): Unit =
    val configuration = parse(arguments.toList)
    Files.createDirectories(configuration.receipt.resolve("raw"))
    val raw = configuration.receipt.resolve("raw/jmh.json")
    val log = configuration.receipt.resolve("raw/jmh.log")
    val builder = new OptionsBuilder()
      .include(
        "frame4s\\.benchmarks\\.JoinRegimeBenchmarks\\.joinExecutionOnly"
      )
      .param("rows", configuration.sizes.map(_.toString)*)
      .param("order", configuration.orders*)
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
    writeCrossovers(configuration.receipt, metrics)
    writeSummary(configuration, metrics, profiled._2)

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

    val sizes = commaSeparated(
      "--sizes",
      "1000,4000,16000,64000,256000,1000000,4000000"
    ).map(_.toInt)
    if sizes.isEmpty || sizes.exists(_ <= 0) then
      throw new IllegalArgumentException("--sizes must contain positive integers")
    val orders = commaSeparated("--orders", JoinRegimeFixture.Orders.mkString(","))
    val invalidOrders = orders.filterNot(JoinRegimeFixture.Orders.contains)
    if invalidOrders.nonEmpty then
      throw new IllegalArgumentException(
        s"unsupported --orders: ${invalidOrders.mkString(", ")}"
      )

    Configuration(
      receipt = Path.of:
        value("--receipt").getOrElse:
          throw new IllegalArgumentException("--receipt <directory> is required")
      ,
      sizes = sizes.distinct,
      orders = orders.distinct,
      heap = value("--heap").getOrElse("8g"),
      quick = arguments.contains("--quick")
    )

  private def profileConfigurations(
      configuration: Configuration
  ): (Vector[Validation], Vector[StageSample]) =
    val validations = Vector.newBuilder[Validation]
    val stages = Vector.newBuilder[StageSample]
    val samples = if configuration.quick then 2 else 5

    configuration.sizes.foreach: rows =>
      configuration.orders.foreach: order =>
        val state = new JoinRegimeState
        state.rows = rows
        state.order = order
        state.setup()
        try
          var warmup = 0
          while warmup < 3 do
            val profiled = state.execution.profileRun()
            val result = profiled.run.result.fold(
              error => throw new IllegalStateException(error.message),
              identity
            )
            result.close()
            warmup += 1

          var expectedChecksum: Option[Long] = None
          var sample = 1
          while sample <= samples do
            val profiled = state.execution.profileRun()
            if profiled.run.receipt.fallback.nonEmpty then
              throw new IllegalStateException(
                s"$rows/$order unexpectedly fell back"
              )
            val result = profiled.run.result.fold(
              error => throw new IllegalStateException(error.message),
              identity
            )
            try
              profiled.stages.foreach: timing =>
                stages += StageSample(
                  rows,
                  order,
                  sample,
                  timing.stage,
                  timing.nanoseconds.toDouble / 1e6
                )
              val checksumStarted = System.nanoTime()
              val checksum = result.checksum.fold(
                error => throw new IllegalStateException(error.message),
                identity
              )
              stages += StageSample(
                rows,
                order,
                sample,
                "checksum",
                (System.nanoTime() - checksumStarted).toDouble / 1e6
              )
              if result.rowCount != rows.toLong then
                throw new IllegalStateException(
                  s"$rows/$order produced ${result.rowCount} rows"
                )
              if checksum != state.expectedChecksum then
                throw new IllegalStateException(
                  s"$rows/$order checksum ${java.lang.Long.toUnsignedString(checksum)} " +
                    s"!= ${java.lang.Long.toUnsignedString(state.expectedChecksum)}"
                )
              expectedChecksum match
                case Some(expected) if expected != checksum =>
                  throw new IllegalStateException(
                    s"$rows/$order checksum changed between profile samples"
                  )
                case None => expectedChecksum = Some(checksum)
                case _    => ()
              if sample == 1 then
                validations += Validation(
                  rows,
                  order,
                  result.rowCount,
                  java.lang.Long.toUnsignedString(checksum),
                  state.leftDigest,
                  state.rightDigest
                )
            finally result.close()
            sample += 1
        finally state.tearDown()

    (validations.result(), stages.result())

  private def extractMetrics(results: Vector[RunResult]): Vector[Metric] =
    results.map: result =>
      val allocation = result.getSecondaryResults.asScala
        .get("gc.alloc.rate.norm")
        .map(_.getScore)
        .getOrElse:
          throw new IllegalStateException("join regime row has no normalized allocation")
      Metric(
        result.getParams.getParam("rows").toInt,
        result.getParams.getParam("order"),
        result.getPrimaryResult.getScore,
        allocation
      )

  private def requireComplete(
      configuration: Configuration,
      metrics: Vector[Metric]
  ): Unit =
    val expected =
      configuration.sizes.flatMap(rows => configuration.orders.map(rows -> _)).toSet
    val actual = metrics.map(metric => metric.rows -> metric.order).toSet
    val missing = expected -- actual
    if missing.nonEmpty then
      throw new IllegalStateException(
        s"join regime court omitted ${missing.toVector.sorted.mkString(", ")}"
      )

  private def writeEnvironment(configuration: Configuration): Unit =
    val runtime = Runtime.getRuntime
    val properties = Vector(
      "receipt_format=1",
      "suite=frame4s-join-regime-court",
      s"quick=${configuration.quick}",
      s"sizes=${configuration.sizes.mkString(",")}",
      s"orders=${configuration.orders.mkString(",")}",
      s"left.seed.unsigned=${java.lang.Long.toUnsignedString(JoinRegimeFixture.LeftSeed)}",
      s"right.seed.unsigned=${java.lang.Long.toUnsignedString(JoinRegimeFixture.RightSeed)}",
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
      "timing=detached-result-construction-with-jmh-blackhole",
      "fixture=identical-key-value-multiset; only row ordering changes"
    )
    write(
      configuration.receipt.resolve("environment.properties"),
      properties.mkString("", "\n", "\n")
    )

  private def writeMetrics(receipt: Path, metrics: Vector[Metric]): Unit =
    val rows = metrics
      .sortBy(metric => (metric.rows, metric.order))
      .map: metric =>
        f"${metric.rows}\t${metric.order}\t${metric.milliseconds}%.9f\t" +
          f"${metric.milliseconds * 1000000.0 / metric.rows.toDouble}%.6f\t" +
          f"${metric.allocation}%.3f"
    write(
      receipt.resolve("metrics.tsv"),
      (
        "rows\torder\tmilliseconds\tnanoseconds_per_row\tallocation_bytes" +: rows
      ).mkString("", "\n", "\n")
    )

  private def writeValidations(
      receipt: Path,
      validations: Vector[Validation]
  ): Unit =
    val rows = validations
      .sortBy(value => (value.rows, value.order))
      .map: value =>
        s"${value.rows}\t${value.order}\t${value.outputRows}\t${value.checksum}\t" +
          s"${value.leftDigest}\t${value.rightDigest}"
    write(
      receipt.resolve("validation.tsv"),
      (
        "rows\torder\toutput_rows\tchecksum\tleft_keys_sha256\tright_keys_sha256" +: rows
      ).mkString("", "\n", "\n")
    )

  private def writeStages(receipt: Path, samples: Vector[StageSample]): Unit =
    val raw = samples.map: value =>
      f"${value.rows}\t${value.order}\t${value.sample}\t${value.stage}\t" +
        f"${value.milliseconds}%.6f"
    write(
      receipt.resolve("raw/stages.tsv"),
      (
        "rows\torder\tsample\tstage\tmilliseconds" +: raw
      ).mkString("", "\n", "\n")
    )

    val summary = samples
      .groupBy(value => (value.rows, value.order, value.stage))
      .toVector
      .sortBy(_._1)
      .map: (key, values) =>
        f"${key._1}\t${key._2}\t${key._3}\t${median(values.map(_.milliseconds))}%.6f"
    write(
      receipt.resolve("stage-summary.tsv"),
      (
        "rows\torder\tstage\tmedian_milliseconds" +: summary
      ).mkString("", "\n", "\n")
    )

  private def writeCrossovers(receipt: Path, metrics: Vector[Metric]): Unit =
    val rows = metrics
      .groupBy(_.order)
      .toVector
      .sortBy(_._1)
      .flatMap: (order, values) =>
        values
          .sortBy(_.rows)
          .sliding(2)
          .collect:
            case Vector(lower, upper) =>
              val lowerPerRow = lower.milliseconds / lower.rows.toDouble
              val upperPerRow = upper.milliseconds / upper.rows.toDouble
              f"$order\t${lower.rows}\t${upper.rows}\t${upperPerRow / lowerPerRow}%.6f"
          .toVector
    write(
      receipt.resolve("crossover-candidates.tsv"),
      (
        "order\tlower_rows\tupper_rows\tper_row_cost_ratio" +: rows
      ).mkString("", "\n", "\n")
    )

  private def writeSummary(
      configuration: Configuration,
      metrics: Vector[Metric],
      stages: Vector[StageSample]
  ): Unit =
    val rows = metrics
      .sortBy(metric => (metric.rows, configuration.orders.indexOf(metric.order)))
      .map: metric =>
        f"| ${metric.rows} | ${metric.order} | ${metric.milliseconds}%.3f | " +
          f"${metric.milliseconds * 1000000.0 / metric.rows.toDouble}%.2f | " +
          f"${metric.allocation / 1000000.0}%.2f |"
    val stageNote =
      if stages.nonEmpty then
        "Median strategy and execution-stage attribution is in `stage-summary.tsv`."
      else "No stage samples were recorded."
    val mode =
      if configuration.quick then "Quick provisional receipt." else "Full receipt."
    val summary =
      s"""@# frame4s join size and key-order regime court
          @
          @$mode Every row constructs and blackholes a detached one-to-one join result.
          @The key/value multiset is identical across orders; only row order changes.
          @
          @| Rows | Key order | Execution ms/op | ns/row | Allocation MB/op |
          @|---:|---|---:|---:|---:|
          @${rows.mkString("\n")}
          @
          @$stageNote `crossover-candidates.tsv` reports the change in per-row cost
          @between every adjacent size. Exact output checksums and deterministic key
          @fingerprints are in `validation.tsv`.
          @""".stripMargin('@')
    write(configuration.receipt.resolve("summary.md"), summary)

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
