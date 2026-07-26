package frame4s.benchmarks

import org.openjdk.jmh.profile.GCProfiler
import org.openjdk.jmh.results.RunResult
import org.openjdk.jmh.results.format.ResultFormatType
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.{OptionsBuilder, TimeValue}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object CourtRunner:
  final private case class Configuration(
      receipt: Path,
      quick: Boolean,
      rows: Int,
      validateOnly: Boolean
  )

  final private case class Validation(
      benchmark: String,
      outputRows: Long,
      checksum: String,
      status: String
  )

  def main(arguments: Array[String]): Unit =
    val configuration = parse(arguments.toList)
    Files.createDirectories(configuration.receipt.resolve("raw"))
    val raw = configuration.receipt.resolve("raw/jmh.json")
    val log = configuration.receipt.resolve("raw/jmh.log")
    val builder = new OptionsBuilder()
      .include("frame4s\\.benchmarks\\..*Benchmarks\\..*")
      .param("rows", configuration.rows.toString)
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

    val validations = validate(configuration.rows)
    requireOracleParity(validations)
    requireComparableParity(validations)
    writeEnvironment(configuration)
    writeValidations(configuration.receipt, validations)
    if !configuration.validateOnly then
      val results = new Runner(builder.build()).run().asScala.toVector
      sanitizeMachinePaths(raw, configuration.receipt)
      sanitizeMachinePaths(log, configuration.receipt)
      requireCompleteCourt(results, validations)
      writeSummary(configuration, results, validations)

  private def parse(arguments: List[String]): Configuration =
    def value(flag: String): Option[String] =
      arguments.sliding(2).collectFirst { case List(`flag`, actual) => actual }

    val receipt = value("--receipt").getOrElse:
      throw new IllegalArgumentException("--receipt <directory> is required")
    val rows = value("--rows").fold(10000)(_.toInt)
    if rows <= 0 then throw new IllegalArgumentException("--rows must be positive")
    Configuration(
      Path.of(receipt),
      arguments.contains("--quick"),
      rows,
      arguments.contains("--validate-only")
    )

  private def validate(rows: Int): Vector[Validation] =
    val referenceState = new ReferenceState
    referenceState.rows = rows
    referenceState.setup()
    val reference = new ReferenceBenchmarks
    val referenceValidations =
      try
        Vector(
          validation(
            "ReferenceBenchmarks.primitiveScan",
            rows,
            reference.primitiveScan(referenceState)
          ),
          validation(
            "ReferenceBenchmarks.nullableScan",
            rows,
            reference.nullableScan(referenceState)
          ),
          validation(
            "ReferenceBenchmarks.filter",
            rows - rows / 2,
            reference.filter(referenceState)
          ),
          validation(
            "ReferenceBenchmarks.fusedFilterProjectArithmetic",
            rows - rows / 2,
            reference.fusedFilterProjectArithmetic(referenceState)
          ),
          validation(
            "ReferenceBenchmarks.groupedLowCardinality",
            math.min(16, rows),
            reference.groupedLowCardinality(referenceState)
          ),
          validation(
            "ReferenceBenchmarks.groupedLowCardinalitySumOnly",
            math.min(16, rows),
            reference.groupedLowCardinalitySumOnly(referenceState)
          ),
          validation(
            "ReferenceBenchmarks.groupedHighCardinality",
            rows,
            reference.groupedHighCardinality(referenceState)
          ),
          validation(
            "ReferenceBenchmarks.joinOneToOne",
            rows,
            reference.joinOneToOne(referenceState)
          ),
          validation(
            "ReferenceBenchmarks.joinOneToMany",
            rows,
            reference.joinOneToMany(referenceState)
          ),
          validation(
            "ReferenceBenchmarks.joinSparse",
            (rows + 9L) / 10L,
            reference.joinSparse(referenceState)
          ),
          validation(
            "ReferenceBenchmarks.joinSkewed",
            math.min(rows, 1000),
            reference.joinSkewed(referenceState)
          ),
          validation(
            "ReferenceBenchmarks.distinctLowCardinality",
            (rows + 2L) / 3L,
            reference.distinctLowCardinality(referenceState)
          ),
          validation(
            "ReferenceBenchmarks.semiJoinSparse",
            (rows + 9L) / 10L,
            reference.semiJoinSparse(referenceState)
          ),
          validation(
            "ReferenceBenchmarks.antiJoinSparse",
            rows - (rows + 9L) / 10L,
            reference.antiJoinSparse(referenceState)
          ),
          validation(
            "ReferenceBenchmarks.unionAll",
            rows * 2L,
            reference.unionAll(referenceState)
          ),
          validation("ReferenceBenchmarks.utf8Scan", rows, reference.utf8Scan(referenceState)),
          validation(
            "ReferenceBenchmarks.dictionaryScan",
            rows,
            reference.dictionaryScan(referenceState)
          ),
          validation(
            "ReferenceBenchmarks.boundedScalarDecode",
            math.min(rows, 32),
            reference.boundedScalarDecode(referenceState)
          ),
          validation(
            "ReferenceBenchmarks.tableConstruction",
            rows,
            reference.tableConstruction(referenceState)
          ),
          validation("ReferenceBenchmarks.csvDecode", rows, reference.csvDecode(referenceState))
        )
      finally referenceState.tearDown()

    val saddleState = new SaddleState
    saddleState.rows = rows
    saddleState.setup()
    val saddle = new SaddleBenchmarks
    val saddleValidations = Vector(
      validation(
        "SaddleBenchmarks.primitiveScan",
        rows,
        saddle.primitiveScan(saddleState),
        "raw scan lower bound; no output materialization"
      ),
      validation(
        "SaddleBenchmarks.primitiveMaterializedProjection",
        rows,
        saddle.primitiveMaterializedProjection(saddleState),
        "semantically equivalent materialized primitive projection"
      ),
      validation(
        "SaddleBenchmarks.filterProjectArithmetic",
        rows - rows / 2,
        saddle.filterProjectArithmetic(saddleState),
        "semantically equivalent materialized pipeline"
      ),
      Validation(
        "SaddleBenchmarks.groupedLowCardinality",
        math.min(16, rows),
        java.lang.Double.toHexString(saddle.groupedLowCardinality(saddleState)),
        "sum-only scalar grouped-reduction lower bound"
      ),
      validation(
        "SaddleBenchmarks.groupedLowCardinalitySumOnly",
        math.min(16, rows),
        saddle.groupedLowCardinalitySumOnly(saddleState),
        "semantically equivalent materialized group-key and sum output"
      )
    )

    val arrayState = new ArrayState
    arrayState.rows = rows
    arrayState.setup()
    val arrays = new SpecializedArrayBenchmarks
    val arrayValidations = Vector(
      validation(
        "SpecializedArrayBenchmarks.nullableScan",
        rows,
        arrays.nullableScan(arrayState),
        "specialized lower-bound baseline"
      ),
      validation(
        "SpecializedArrayBenchmarks.fusedFilterProjectArithmetic",
        rows - rows / 2,
        arrays.fusedFilterProjectArithmetic(arrayState),
        "specialized lower-bound baseline"
      )
    )

    val columnarState = new ReferenceState
    columnarState.rows = rows
    columnarState.setup()
    val columnar = new ColumnarBenchmarks
    val columnarValidations =
      try
        Vector(
          validation(
            "ColumnarBenchmarks.primitiveScan",
            rows,
            columnar.primitiveScan(columnarState),
            "candidate kernel; oracle checksum required"
          ),
          validation(
            "ColumnarBenchmarks.nullableScan",
            rows,
            columnar.nullableScan(columnarState),
            "candidate kernel; oracle checksum required"
          ),
          validation(
            "ColumnarBenchmarks.utf8Scan",
            rows,
            columnar.utf8Scan(columnarState),
            "candidate kernel; oracle checksum required"
          ),
          validation(
            "ColumnarBenchmarks.dictionaryScan",
            rows,
            columnar.dictionaryScan(columnarState),
            "candidate kernel; oracle checksum required"
          ),
          validation(
            "ColumnarBenchmarks.filter",
            rows - rows / 2,
            columnar.filter(columnarState),
            "candidate kernel; oracle checksum required"
          ),
          validation(
            "ColumnarBenchmarks.fusedFilterProjectArithmetic",
            rows - rows / 2,
            columnar.fusedFilterProjectArithmetic(columnarState),
            "candidate kernel; oracle checksum required"
          ),
          validation(
            "ColumnarBenchmarks.groupedLowCardinality",
            math.min(16, rows),
            columnar.groupedLowCardinality(columnarState),
            "candidate kernel; oracle checksum required"
          ),
          validation(
            "ColumnarBenchmarks.groupedLowCardinalitySumOnly",
            math.min(16, rows),
            columnar.groupedLowCardinalitySumOnly(columnarState),
            "candidate kernel; oracle checksum required"
          ),
          validation(
            "ColumnarBenchmarks.groupedHighCardinality",
            rows,
            columnar.groupedHighCardinality(columnarState),
            "candidate kernel; oracle checksum required"
          ),
          validation(
            "ColumnarBenchmarks.joinOneToOne",
            rows,
            columnar.joinOneToOne(columnarState),
            "candidate kernel; oracle checksum required"
          ),
          validation(
            "ColumnarBenchmarks.joinOneToMany",
            rows,
            columnar.joinOneToMany(columnarState),
            "candidate kernel; oracle checksum required"
          ),
          validation(
            "ColumnarBenchmarks.joinSparse",
            (rows + 9L) / 10L,
            columnar.joinSparse(columnarState),
            "candidate kernel; oracle checksum required"
          ),
          validation(
            "ColumnarBenchmarks.joinSkewed",
            math.min(rows, 1000),
            columnar.joinSkewed(columnarState),
            "candidate kernel; oracle checksum required"
          ),
          validation(
            "ColumnarBenchmarks.distinctLowCardinality",
            (rows + 2L) / 3L,
            columnar.distinctLowCardinality(columnarState),
            "candidate kernel; oracle checksum required"
          ),
          validation(
            "ColumnarBenchmarks.semiJoinSparse",
            (rows + 9L) / 10L,
            columnar.semiJoinSparse(columnarState),
            "candidate kernel; oracle checksum required"
          ),
          validation(
            "ColumnarBenchmarks.antiJoinSparse",
            rows - (rows + 9L) / 10L,
            columnar.antiJoinSparse(columnarState),
            "candidate kernel; oracle checksum required"
          ),
          validation(
            "ColumnarBenchmarks.unionAll",
            rows * 2L,
            columnar.unionAll(columnarState),
            "candidate kernel; oracle checksum required"
          )
        )
      finally columnarState.tearDown()

    referenceValidations ++ saddleValidations ++ arrayValidations ++ columnarValidations

  private def validation(
      benchmark: String,
      outputRows: Long,
      checksum: Long,
      status: String = "reference result"
  ): Validation =
    Validation(benchmark, outputRows, java.lang.Long.toUnsignedString(checksum), status)

  private def writeEnvironment(configuration: Configuration): Unit =
    val runtime = Runtime.getRuntime
    val properties = Vector(
      "receipt_format=1",
      "suite=frame4s-performance-court",
      s"quick=${configuration.quick}",
      s"validate_only=${configuration.validateOnly}",
      s"rows=${configuration.rows}",
      s"java.version=${System.getProperty("java.version")}",
      s"java.vendor=${System.getProperty("java.vendor")}",
      s"java.vm.name=${System.getProperty("java.vm.name")}",
      s"os.name=${System.getProperty("os.name")}",
      s"os.version=${System.getProperty("os.version")}",
      s"os.arch=${System.getProperty("os.arch")}",
      s"processors=${runtime.availableProcessors()}",
      s"runner.max.heap.bytes=${runtime.maxMemory()}",
      "benchmark.heap=-Xms1g,-Xmx1g",
      "benchmark.jvm.flags=-XX:+AlwaysPreTouch",
      s"hardware=${sys.env.getOrElse("FRAME4S_HARDWARE", "unrecorded")}",
      "scala.version=3.7.4",
      "sbt.version=1.10.5",
      "jmh.version=1.37",
      "sbt-jmh.version=0.4.8",
      "saddle.version=4.0.0-M14",
      "frame4s.backends=semantic-reference,columnar-candidate",
      "frame4s.columnar.fallback=forbidden-in-candidate-benchmarks"
    )
    write(
      configuration.receipt.resolve("environment.properties"),
      properties.mkString("", "\n", "\n")
    )

  private def writeValidations(receipt: Path, validations: Vector[Validation]): Unit =
    val lines = validations.map: item =>
      Vector(item.benchmark, item.outputRows.toString, item.checksum, item.status).mkString("\t")
    write(
      receipt.resolve("validation.tsv"),
      ("benchmark\toutput_rows\tchecksum\tstatus" +: lines).mkString("", "\n", "\n")
    )

  private def writeSummary(
      configuration: Configuration,
      results: Vector[RunResult],
      validations: Vector[Validation]
  ): Unit =
    val validationByBenchmark = validations.map(value => value.benchmark -> value).toMap
    val rows = results
      .sortBy: result =>
        val params = result.getParams
        (params.getBenchmark, params.getMode.shortLabel())
      .map: result =>
        val params = result.getParams
        val benchmark = params.getBenchmark.split('.').takeRight(2).mkString(".")
        val primary = result.getPrimaryResult
        val secondary = result.getSecondaryResults.asScala
        val allocation = secondary
          .get("gc.alloc.rate.norm")
          .map(value => f"${value.getScore}%.3f ${value.getScoreUnit}")
          .getOrElse("not reported")
        val validation = validationByBenchmark.get(benchmark)
        val outputRows = validation.fold("n/a")(_.outputRows.toString)
        val checksum = validation.fold("n/a")(_.checksum)
        val status = validation.fold("unvalidated")(_.status)
        val score = f"${primary.getScore}%.6g ${primary.getScoreUnit}"
        s"| `$benchmark` | ${params.getMode.shortLabel()} | $score | $allocation | $outputRows | `$checksum` | $status |"

    val mode =
      if configuration.quick then
        "Quick court receipt. It validates harness wiring and produces provisional measurements; it is not a release performance claim."
      else
        "Full court receipt using the committed warmup, measurement, fork, heap, and profiler settings."
    val summary =
      s"""@# frame4s benchmark court receipt
         @
         @$mode
         @
         @The semantic reference interpreter is an executable oracle, not the optimized backend.
         @Every `ColumnarBenchmarks` row refuses fallback and must carry the same checksum and
         @output cardinality as its corresponding `ReferenceBenchmarks` row.
         @The materialized primitive projection, fused filter/project, and materialized nullable
         @grouped-sum Saddle rows are comparable. The raw primitive scan and scalar grouped
         @reduction remain explicit lower bounds, not win/loss comparators. SQL duplicate-key
         @joins, dictionary layout, CSV acquisition, and owned-table construction have no claimed
         @Saddle-equivalent result. Scautable is intentionally excluded from relational rankings.
         @
         @| Benchmark | Mode | Time or throughput | Allocation | Output rows | Checksum | Comparison status |
         @|---|---:|---:|---:|---:|---:|---|
         @${rows.mkString("\n")}
         @
         @Raw JMH JSON and console output are in `raw/`. `validation.tsv` records a
         @single untimed execution of every workload so row counts and checksums travel
         @with the timing and allocation receipt. `environment.properties` records the
         @toolchain, hardware description, fixture size, backend, and fallback status.
         @""".stripMargin('@')
    write(configuration.receipt.resolve("summary.md"), summary)

  private def requireCompleteCourt(
      results: Vector[RunResult],
      validations: Vector[Validation]
  ): Unit =
    val measured = results.iterator
      .map(_.getParams.getBenchmark.split('.').takeRight(2).mkString("."))
      .toSet
    val expected = validations.iterator.map(_.benchmark).toSet
    val missing = (expected -- measured).toVector.sorted
    if missing.nonEmpty then
      throw new IllegalStateException(
        s"JMH court omitted validated benchmarks: ${missing.mkString(", ")}"
      )

  private def requireOracleParity(validations: Vector[Validation]): Unit =
    val byBenchmark = validations.map(value => value.benchmark -> value).toMap
    val mismatches = validations
      .collect:
        case candidate if candidate.benchmark.startsWith("ColumnarBenchmarks.") =>
          val suffix = candidate.benchmark.stripPrefix("ColumnarBenchmarks.")
          val oracleName = s"ReferenceBenchmarks.$suffix"
          byBenchmark.get(oracleName) match
            case Some(oracle)
                if oracle.outputRows == candidate.outputRows &&
                  oracle.checksum == candidate.checksum =>
              None
            case Some(oracle) =>
              Some(
                s"${candidate.benchmark}=${candidate.outputRows}/${candidate.checksum}, " +
                  s"$oracleName=${oracle.outputRows}/${oracle.checksum}"
              )
            case None => Some(s"${candidate.benchmark} has no $oracleName validation")
      .flatten
    if mismatches.nonEmpty then
      throw new IllegalStateException(
        s"columnar validation disagreed with the oracle: ${mismatches.mkString("; ")}"
      )

  private def requireComparableParity(validations: Vector[Validation]): Unit =
    val byBenchmark = validations.map(value => value.benchmark -> value).toMap
    val pairs = Vector(
      "SaddleBenchmarks.primitiveMaterializedProjection" ->
        "ReferenceBenchmarks.primitiveScan",
      "SaddleBenchmarks.groupedLowCardinalitySumOnly" ->
        "ReferenceBenchmarks.groupedLowCardinalitySumOnly"
    )
    val mismatches = pairs.flatMap: (comparatorName, oracleName) =>
      (byBenchmark.get(comparatorName), byBenchmark.get(oracleName)) match
        case (Some(comparator), Some(oracle))
            if comparator.outputRows == oracle.outputRows &&
              comparator.checksum == oracle.checksum =>
          None
        case (Some(comparator), Some(oracle)) =>
          Some(
            s"$comparatorName=${comparator.outputRows}/${comparator.checksum}, " +
              s"$oracleName=${oracle.outputRows}/${oracle.checksum}"
          )
        case _ => Some(s"missing comparable validation pair $comparatorName/$oracleName")
    if mismatches.nonEmpty then
      throw new IllegalStateException(
        s"comparable Saddle validation disagreed with the oracle: ${mismatches.mkString("; ")}"
      )

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
