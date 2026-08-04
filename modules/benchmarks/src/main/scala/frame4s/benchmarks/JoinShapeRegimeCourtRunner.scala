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

/** Measures sparse and existential joins across adjacent sizes and shuffled keys. */
object JoinShapeRegimeCourtRunner:
  final private case class Configuration(
      receipt: Path,
      sizes: Vector[Int],
      workloads: Vector[String],
      orders: Vector[String],
      heap: String,
      quick: Boolean
  )

  final private case class Metric(
      rows: Int,
      workload: String,
      order: String,
      milliseconds: Double,
      allocation: Double
  )

  final private case class Validation(
      rows: Int,
      workload: String,
      order: String,
      outputRows: Long,
      checksum: String,
      leftDigest: String,
      rightDigest: String,
      rightValueDigest: String
  )

  def main(arguments: Array[String]): Unit =
    val configuration = parse(arguments.toList)
    Files.createDirectories(configuration.receipt.resolve("raw"))
    val raw = configuration.receipt.resolve("raw/jmh.json")
    val log = configuration.receipt.resolve("raw/jmh.log")
    val builder = new OptionsBuilder()
      .include(
        "frame4s\\.benchmarks\\.JoinShapeRegimeBenchmarks\\.joinExecutionOnly"
      )
      .param("rows", configuration.sizes.map(_.toString)*)
      .param("workload", configuration.workloads*)
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

    val validations = validate(configuration)
    val metrics = new Runner(builder.build()).run().asScala.toVector.map(metric)
    requireComplete(configuration, metrics)
    sanitizeMachinePaths(raw, configuration.receipt)
    sanitizeMachinePaths(log, configuration.receipt)
    writeEnvironment(configuration)
    writeMetrics(configuration.receipt, metrics)
    writeValidations(configuration.receipt, validations)
    writeSummary(configuration, metrics)

  private def parse(arguments: List[String]): Configuration =
    def value(flag: String): Option[String] =
      arguments.sliding(2).collectFirst { case List(`flag`, actual) => actual }

    def commaSeparated(flag: String, default: Vector[String]): Vector[String] =
      value(flag)
        .fold(default)(_.split(',').iterator.map(_.trim).filter(_.nonEmpty).toVector)
        .distinct

    val sizes =
      commaSeparated("--sizes", Vector("256000", "1000000", "4000000")).map(_.toInt)
    val workloads =
      commaSeparated("--workloads", JoinShapeRegimeFixture.Workloads)
    val orders = commaSeparated("--orders", JoinShapeRegimeFixture.Orders)
    if sizes.isEmpty || sizes.exists(_ <= 0) then
      throw new IllegalArgumentException("--sizes must contain positive integers")
    val invalidWorkloads = workloads.filterNot(JoinShapeRegimeFixture.Workloads.contains)
    if invalidWorkloads.nonEmpty then
      throw new IllegalArgumentException(
        s"unsupported workloads: ${invalidWorkloads.mkString(", ")}"
      )
    val invalidOrders = orders.filterNot(JoinShapeRegimeFixture.Orders.contains)
    if invalidOrders.nonEmpty then
      throw new IllegalArgumentException(
        s"unsupported orders: ${invalidOrders.mkString(", ")}"
      )
    Configuration(
      receipt = Path.of:
        value("--receipt").getOrElse:
          throw new IllegalArgumentException("--receipt <directory> is required")
      ,
      sizes = sizes,
      workloads = workloads,
      orders = orders,
      heap = value("--heap").getOrElse("8g"),
      quick = arguments.contains("--quick")
    )

  private def validate(configuration: Configuration): Vector[Validation] =
    configuration.sizes.flatMap: rows =>
      configuration.workloads.flatMap: workload =>
        configuration.orders.map: order =>
          val state = new JoinShapeRegimeState
          state.rows = rows
          state.workload = workload
          state.order = order
          state.setup()
          try
            var expected: Option[Long] = None
            var outputRows = 0L
            var repeat = 0
            while repeat < 3 do
              val run = state.execution.run()
              if run.receipt.fallback.nonEmpty then
                throw new IllegalStateException(
                  s"$rows/$workload/$order unexpectedly fell back"
                )
              val result = run.result.fold(
                error => throw new IllegalStateException(error.message),
                identity
              )
              try
                outputRows = result.rowCount
                val checksum = result.checksum.fold(
                  error => throw new IllegalStateException(error.message),
                  identity
                )
                if outputRows != state.expectedRows || checksum != state.expectedChecksum then
                  throw new IllegalStateException(
                    s"$rows/$workload/$order produced $outputRows/" +
                      s"${java.lang.Long.toUnsignedString(checksum)}; expected " +
                      s"${state.expectedRows}/" +
                      java.lang.Long.toUnsignedString(state.expectedChecksum)
                  )
                expected match
                  case Some(value) if value != checksum =>
                    throw new IllegalStateException(
                      s"$rows/$workload/$order checksum changed between runs"
                    )
                  case None        => expected = Some(checksum)
                  case Some(value) => ()
              finally result.close()
              repeat += 1
            Validation(
              rows,
              workload,
              order,
              outputRows,
              java.lang.Long.toUnsignedString(expected.get),
              state.leftDigest,
              state.rightDigest,
              state.rightValueDigest
            )
          finally state.tearDown()

  private def metric(result: RunResult): Metric =
    val allocation = result.getSecondaryResults.asScala
      .get("gc.alloc.rate.norm")
      .map(_.getScore)
      .getOrElse:
        throw new IllegalStateException("shape regime row has no allocation result")
    Metric(
      result.getParams.getParam("rows").toInt,
      result.getParams.getParam("workload"),
      result.getParams.getParam("order"),
      result.getPrimaryResult.getScore,
      allocation
    )

  private def requireComplete(
      configuration: Configuration,
      metrics: Vector[Metric]
  ): Unit =
    val expected =
      (for
        rows <- configuration.sizes
        workload <- configuration.workloads
        order <- configuration.orders
      yield (rows, workload, order)).toSet
    val actual = metrics.map(value => (value.rows, value.workload, value.order)).toSet
    if actual != expected then
      throw new IllegalStateException(
        s"shape court mismatch: missing=${expected -- actual}, extra=${actual -- expected}"
      )

  private def writeEnvironment(configuration: Configuration): Unit =
    val properties = Vector(
      "receipt_format=1",
      "suite=frame4s-join-shape-regime-court",
      s"quick=${configuration.quick}",
      s"sizes=${configuration.sizes.mkString(",")}",
      s"workloads=${configuration.workloads.mkString(",")}",
      s"orders=${configuration.orders.mkString(",")}",
      s"java.version=${System.getProperty("java.version")}",
      s"os.name=${System.getProperty("os.name")}",
      s"os.version=${System.getProperty("os.version")}",
      s"os.arch=${System.getProperty("os.arch")}",
      s"processors=${Runtime.getRuntime.availableProcessors()}",
      s"hardware=${sys.env.getOrElse("FRAME4S_HARDWARE", "unrecorded")}",
      s"benchmark.heap=-Xms${configuration.heap},-Xmx${configuration.heap}",
      "benchmark.mode=average-time",
      "benchmark.profiler=gc",
      "timing=detached-result-construction-with-jmh-blackhole"
    )
    write(
      configuration.receipt.resolve("environment.properties"),
      properties.mkString("", "\n", "\n")
    )

  private def writeMetrics(receipt: Path, metrics: Vector[Metric]): Unit =
    val rows = metrics
      .sortBy(value => (value.rows, value.workload, value.order))
      .map: value =>
        f"${value.rows}\t${value.workload}\t${value.order}\t" +
          f"${value.milliseconds}%.9f\t${value.allocation}%.3f"
    write(
      receipt.resolve("metrics.tsv"),
      (
        "rows\tworkload\torder\tmilliseconds\tallocation_bytes" +: rows
      ).mkString("", "\n", "\n")
    )

  private def writeValidations(
      receipt: Path,
      validations: Vector[Validation]
  ): Unit =
    val rows = validations
      .sortBy(value => (value.rows, value.workload, value.order))
      .map: value =>
        s"${value.rows}\t${value.workload}\t${value.order}\t${value.outputRows}\t" +
          s"${value.checksum}\t${value.leftDigest}\t${value.rightDigest}\t" +
          value.rightValueDigest
    write(
      receipt.resolve("validation.tsv"),
      (
        (
          "rows\tworkload\torder\toutput_rows\tchecksum\tleft_keys_sha256\t" +
            "right_keys_sha256\tright_values_sha256"
        ) +: rows
      ).mkString("", "\n", "\n")
    )

  private def writeSummary(
      configuration: Configuration,
      metrics: Vector[Metric]
  ): Unit =
    val rows = metrics
      .sortBy(value => (value.rows, value.workload, value.order))
      .map: value =>
        f"| ${value.rows} | ${value.workload} | ${value.order} | " +
          f"${value.milliseconds}%.3f | ${value.allocation / 1000000.0}%.2f |"
    val mode = if configuration.quick then "Quick provisional receipt." else "Full receipt."
    val summary =
      s"""@# frame4s join-shape regime court
          @
          @$mode Every row constructs and blackholes a detached join result.
          @
          @| Rows | Workload | Key order | Execution ms/op | Allocation MB/op |
          @|---:|---|---|---:|---:|
          @${rows.mkString("\n")}
          @
          @Exact checksums and deterministic fixture fingerprints are in
          @`validation.tsv`; individual JMH samples are in `raw/jmh.json`.
          @""".stripMargin('@')
    write(configuration.receipt.resolve("summary.md"), summary)

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
