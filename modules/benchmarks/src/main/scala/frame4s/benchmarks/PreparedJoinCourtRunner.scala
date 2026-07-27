package frame4s.benchmarks

import org.openjdk.jmh.profile.GCProfiler
import org.openjdk.jmh.results.RunResult
import org.openjdk.jmh.results.format.ResultFormatType
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.{OptionsBuilder, TimeValue}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object PreparedJoinCourtRunner:
  final private case class Configuration(
      receipt: Path,
      baseline: Path,
      quick: Boolean
  )

  final private case class Validation(
      method: String,
      outputRows: Long,
      checksum: String
  )

  private val BaselineRow =
    raw"""^\| `ColumnarBenchmarks\.([^`]+)` \| avgt \| ([0-9.Ee+-]+) ms/op \|.*$$""".r

  def main(arguments: Array[String]): Unit =
    val configuration = parse(arguments.toList)
    Files.createDirectories(configuration.receipt.resolve("raw"))
    val raw = configuration.receipt.resolve("raw/jmh.json")
    val log = configuration.receipt.resolve("raw/jmh.log")
    val builder = new OptionsBuilder()
      .include(
        "frame4s\\.benchmarks\\.(PreparedJoinCourt|PreparedJoinBuildCourt)\\..*|" +
          "frame4s\\.benchmarks\\.ColumnarBenchmarks\\.(joinOneToOne|joinOneToMany|joinSparse|joinSkewed)"
      )
      .param("rows", "1000")
      .result(raw.toString)
      .resultFormat(ResultFormatType.JSON)
      .output(log.toString)
      .addProfiler(classOf[GCProfiler])

    if configuration.quick then
      val _ = builder
        .warmupIterations(1)
        .warmupTime(TimeValue.milliseconds(100))
        .measurementIterations(1)
        .measurementTime(TimeValue.milliseconds(150))
        .forks(1)

    val validations = validate()
    writeEnvironment(configuration)
    writeValidations(configuration.receipt, validations)
    val results = new Runner(builder.build()).run().asScala.toVector
    sanitizeMachinePaths(raw, configuration.receipt)
    sanitizeMachinePaths(log, configuration.receipt)
    writeSummary(configuration, results, validations)

  private def parse(arguments: List[String]): Configuration =
    def value(flag: String): Option[String] =
      arguments.sliding(2).collectFirst { case List(`flag`, actual) => actual }
    Configuration(
      Path.of:
        value("--receipt").getOrElse:
          throw new IllegalArgumentException("--receipt <directory> is required")
      ,
      Path.of:
        value("--baseline").getOrElse:
          throw new IllegalArgumentException("--baseline <directory> is required")
      ,
      arguments.contains("--quick")
    )

  private def validate(): Vector[Validation] =
    val state = new PreparedJoinState
    state.rows = 1000
    state.setup()
    val oneShot = new ColumnarBenchmarks
    val prepared = new PreparedJoinCourt
    try
      Vector(
        exact(
          "joinOneToOne",
          1000,
          oneShot.joinOneToOne(state),
          prepared.joinOneToOne(state)
        ),
        exact(
          "joinOneToMany",
          1000,
          oneShot.joinOneToMany(state),
          prepared.joinOneToMany(state)
        ),
        exact(
          "joinSparse",
          100,
          oneShot.joinSparse(state),
          prepared.joinSparse(state)
        ),
        exact(
          "joinSkewed",
          1000,
          oneShot.joinSkewed(state),
          prepared.joinSkewed(state)
        )
      )
    finally state.tearDown()

  private def exact(
      method: String,
      outputRows: Long,
      oneShot: Long,
      prepared: Long
  ): Validation =
    if oneShot != prepared then
      throw new IllegalStateException(
        s"$method differs between one-shot and prepared execution"
      )
    Validation(method, outputRows, java.lang.Long.toUnsignedString(prepared))

  private def writeEnvironment(configuration: Configuration): Unit =
    val properties = Vector(
      "receipt_format=1",
      "suite=frame4s-prepared-join-court",
      s"quick=${configuration.quick}",
      "rows=1000",
      "index=plain-int32-explicit-prepared",
      s"baseline=${configuration.baseline.getFileName}",
      s"java.version=${System.getProperty("java.version")}",
      s"java.vendor=${System.getProperty("java.vendor")}",
      s"os.name=${System.getProperty("os.name")}",
      s"os.version=${System.getProperty("os.version")}",
      s"os.arch=${System.getProperty("os.arch")}",
      s"hardware=${sys.env.getOrElse("FRAME4S_HARDWARE", "unrecorded")}",
      "scala.version=3.7.4",
      "jmh.version=1.37"
    )
    write(
      configuration.receipt.resolve("environment.properties"),
      properties.mkString("", "\n", "\n")
    )

  private def writeValidations(receipt: Path, validations: Vector[Validation]): Unit =
    val rows = validations.map: value =>
      s"${value.method}\t${value.outputRows}\t${value.checksum}\texact parity with one-shot hash join"
    write(
      receipt.resolve("validation.tsv"),
      (
        "workload\toutput_rows\tchecksum\tstatus" +: rows
      ).mkString("", "\n", "\n")
    )

  private def writeSummary(
      configuration: Configuration,
      results: Vector[RunResult],
      validations: Vector[Validation]
  ): Unit =
    val baseline = readBaseline(configuration.baseline)
    var generallyAdmitted = true
    val rows = validations.map: validation =>
      val frozenOneShot = baseline.getOrElse(
        validation.method,
        throw new IllegalStateException(s"baseline omits ${validation.method}")
      )
      val oneShot =
        averageScore(results, "ColumnarBenchmarks", validation.method)
      val warm = averageScore(results, "PreparedJoinCourt", validation.method)
      val build = averageScore(results, "PreparedJoinBuildCourt", validation.method)
      val cold = build + warm
      val warmSpeedup = oneShot / warm
      val coldSpeedup = oneShot / cold
      generallyAdmitted &&= warm < oneShot && cold < oneShot
      val breakEven =
        if oneShot > warm then f"${build / (oneShot - warm)}%.2f"
        else "never"
      f"| ${validation.method} | $frozenOneShot%.6f | $oneShot%.6f | $warm%.6f | $warmSpeedup%.2fx | $build%.6f | $cold%.6f | $coldSpeedup%.2fx | $breakEven |"
    val mode =
      if configuration.quick then "Quick provisional receipt."
      else "Full prepared-join receipt."
    val decision =
      if generallyAdmitted then "Decision: admit prepared join reuse for the designated shapes."
      else
        "Decision: do not generally admit prepared join reuse. At least one designated shape fails to improve both warm execution and construction plus first execution; the entry point remains package-internal experimental evidence."
    val summary =
      s"""@# frame4s prepared join index
         @
         @$mode Prepared execution reuses the right-side immutable index and detached
         @right columns. Ordinary `ColumnarInterpreter.prepare` remains one-shot.
         @
         @$decision
         @
         @| Workload | Frozen one-shot ms/op | Same-run one-shot ms/op | Warm prepared ms/op | Warm speedup | Build ms | Cold first run ms | Cold speedup | Break-even runs |
         @|---|---:|---:|---:|---:|---:|---:|---:|---:|
         @${rows.mkString("\n")}
         @
         @Cold first run is `build + warm run`; break-even includes construction.
         @Exact output cardinalities and checksums are in `validation.tsv`; raw JMH
         @results are under `raw/`. Gate calculations use the same-run one-shot
         @scores; the frozen R5c Saddle-exact scores are shown as a drift check.
         @""".stripMargin('@')
    write(configuration.receipt.resolve("summary.md"), summary)

  private def readBaseline(directory: Path): Map[String, Double] =
    val summary = directory.resolve("summary.md")
    Files
      .readAllLines(summary, StandardCharsets.UTF_8)
      .asScala
      .collect:
        case BaselineRow(method, milliseconds) => method -> milliseconds.toDouble
      .toMap

  private def averageScore(
      results: Vector[RunResult],
      benchmarkClass: String,
      method: String
  ): Double =
    results
      .find: result =>
        val params = result.getParams
        params.getBenchmark.endsWith(s"$benchmarkClass.$method") &&
        params.getMode.shortLabel() == "avgt"
      .getOrElse:
        throw new IllegalStateException(
          s"JMH result omits $benchmarkClass.$method in average-time mode"
        )
      .getPrimaryResult
      .getScore

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
