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
  /** Which court a receipt belongs to.
    *
    * `Small` is the ratified court: every backend runs, and the semantic reference interpreter is
    * the checksum oracle for the candidate.
    *
    * `Scale` exists because that oracle cannot follow the court to large fixtures. The reference
    * join is a full nested-loop cross product, so a 1,000,000-row join is on the order of 10^12
    * predicate evaluations. The scale tier therefore measures the candidate alone and states so in
    * the receipt; candidate/reference agreement is established by the cross-platform conformance
    * laws and by the `Small` tier, never assumed here.
    */
  private enum Tier:
    case Small
    case Scale

    def id: String = this match
      case Small => "small"
      case Scale => "scale"

    def include: String = this match
      case Small => "frame4s\\.benchmarks\\..*Benchmarks\\..*"
      case Scale => "frame4s\\.benchmarks\\.ColumnarBenchmarks\\..*"

  final private case class Configuration(
      receipt: Path,
      quick: Boolean,
      rows: Int,
      validateOnly: Boolean,
      tier: Tier,
      heap: String
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
      .include(configuration.tier.include)
      .param("rows", configuration.rows.toString)
      .result(raw.toString)
      .resultFormat(ResultFormatType.JSON)
      .output(log.toString)
      .addProfiler(classOf[GCProfiler])

    // The benchmark classes pin a 1 GiB heap, which no longer holds the fixture at scale.
    // Appending wins over the annotation, and the chosen heap is recorded in the receipt.
    if configuration.tier == Tier.Scale then
      val _ = builder.jvmArgsAppend(s"-Xms${configuration.heap}", s"-Xmx${configuration.heap}")

    if configuration.quick then
      val _ = builder
        .warmupIterations(1)
        .warmupTime(TimeValue.milliseconds(100))
        .measurementIterations(1)
        .measurementTime(TimeValue.milliseconds(150))
        .forks(1)

    val validations = configuration.tier match
      case Tier.Small =>
        val all = validate(configuration.rows)
        requireOracleParity(all)
        requireComparableParity(all)
        all
      case Tier.Scale =>
        validateCandidate(
          configuration.rows,
          "candidate kernel; self-consistency only, no oracle at this tier"
        )
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
    val tier = value("--tier").fold(Tier.Small):
      case "small" => Tier.Small
      case "scale" => Tier.Scale
      case other   =>
        throw new IllegalArgumentException(s"--tier must be small or scale; got '$other'")
    Configuration(
      Path.of(receipt),
      arguments.contains("--quick"),
      rows,
      arguments.contains("--validate-only"),
      tier,
      value("--heap").getOrElse("8g")
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
            "ReferenceBenchmarks.fusedFilterProjectArithmeticScattered",
            rows - rows / 2,
            reference.fusedFilterProjectArithmeticScattered(referenceState)
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
        "contiguous-suffix materialized pipeline; retained as a diagnostic shape"
      ),
      validation(
        "SaddleBenchmarks.filterProjectArithmeticScattered",
        rows - rows / 2,
        saddle.filterProjectArithmeticScattered(saddleState),
        "semantically equivalent scattered-selection materialized pipeline"
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
        "contiguous-suffix specialized lower-bound baseline"
      ),
      validation(
        "SpecializedArrayBenchmarks.fusedFilterProjectArithmeticScattered",
        rows - rows / 2,
        arrays.fusedFilterProjectArithmeticScattered(arrayState),
        "scattered-selection specialized lower-bound baseline"
      )
    )

    referenceValidations ++ saddleValidations ++ arrayValidations ++
      validateCandidate(rows, "candidate kernel; oracle checksum required")

  /** Untimed candidate execution, recording output rows and checksums.
    *
    * At the `Small` tier the caller cross-checks every row against the reference oracle. At the
    * `Scale` tier no oracle exists, so these values stand alone and the receipt says so.
    */
  private def validateCandidate(rows: Int, status: String): Vector[Validation] =
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
            status
          ),
          validation(
            "ColumnarBenchmarks.nullableScan",
            rows,
            columnar.nullableScan(columnarState),
            status
          ),
          validation(
            "ColumnarBenchmarks.utf8Scan",
            rows,
            columnar.utf8Scan(columnarState),
            status
          ),
          validation(
            "ColumnarBenchmarks.dictionaryScan",
            rows,
            columnar.dictionaryScan(columnarState),
            status
          ),
          validation(
            "ColumnarBenchmarks.filter",
            rows - rows / 2,
            columnar.filter(columnarState),
            status
          ),
          validation(
            "ColumnarBenchmarks.fusedFilterProjectArithmetic",
            rows - rows / 2,
            columnar.fusedFilterProjectArithmetic(columnarState),
            status
          ),
          validation(
            "ColumnarBenchmarks.fusedFilterProjectArithmeticScattered",
            rows - rows / 2,
            columnar.fusedFilterProjectArithmeticScattered(columnarState),
            status
          ),
          validation(
            "ColumnarBenchmarks.groupedLowCardinality",
            math.min(16, rows),
            columnar.groupedLowCardinality(columnarState),
            status
          ),
          validation(
            "ColumnarBenchmarks.groupedLowCardinalitySumOnly",
            math.min(16, rows),
            columnar.groupedLowCardinalitySumOnly(columnarState),
            status
          ),
          validation(
            "ColumnarBenchmarks.groupedHighCardinality",
            rows,
            columnar.groupedHighCardinality(columnarState),
            status
          ),
          validation(
            "ColumnarBenchmarks.joinOneToOne",
            rows,
            columnar.joinOneToOne(columnarState),
            status
          ),
          validation(
            "ColumnarBenchmarks.joinOneToMany",
            rows,
            columnar.joinOneToMany(columnarState),
            status
          ),
          validation(
            "ColumnarBenchmarks.joinSparse",
            (rows + 9L) / 10L,
            columnar.joinSparse(columnarState),
            status
          ),
          validation(
            "ColumnarBenchmarks.joinSkewed",
            math.min(rows, 1000),
            columnar.joinSkewed(columnarState),
            status
          ),
          validation(
            "ColumnarBenchmarks.distinctLowCardinality",
            (rows + 2L) / 3L,
            columnar.distinctLowCardinality(columnarState),
            status
          ),
          validation(
            "ColumnarBenchmarks.semiJoinSparse",
            (rows + 9L) / 10L,
            columnar.semiJoinSparse(columnarState),
            status
          ),
          validation(
            "ColumnarBenchmarks.antiJoinSparse",
            rows - (rows + 9L) / 10L,
            columnar.antiJoinSparse(columnarState),
            status
          ),
          validation(
            "ColumnarBenchmarks.unionAll",
            rows * 2L,
            columnar.unionAll(columnarState),
            status
          )
        )
      finally columnarState.tearDown()

    columnarValidations

  private def validation(
      benchmark: String,
      outputRows: Long,
      checksum: Long,
      status: String = "reference result"
  ): Validation =
    Validation(benchmark, outputRows, java.lang.Long.toUnsignedString(checksum), status)

  private def writeEnvironment(configuration: Configuration): Unit =
    val runtime = Runtime.getRuntime
    val scatteredIds = FusedPipelineFixture.scatteredIds(configuration.rows)
    val properties = Vector(
      "receipt_format=1",
      "suite=frame4s-performance-court",
      s"quick=${configuration.quick}",
      s"validate_only=${configuration.validateOnly}",
      s"rows=${configuration.rows}",
      s"tier=${configuration.tier.id}",
      s"tier.oracle=${configuration.tier match
          case Tier.Small => "semantic-reference-interpreter"
          case Tier.Scale =>
            "candidate-only; reference join is a nested-loop cross product and " +
              "cannot execute at this fixture size"
        }",
      s"java.version=${System.getProperty("java.version")}",
      s"java.vendor=${System.getProperty("java.vendor")}",
      s"java.vm.name=${System.getProperty("java.vm.name")}",
      s"os.name=${System.getProperty("os.name")}",
      s"os.version=${System.getProperty("os.version")}",
      s"os.arch=${System.getProperty("os.arch")}",
      s"processors=${runtime.availableProcessors()}",
      s"runner.max.heap.bytes=${runtime.maxMemory()}",
      s"benchmark.heap=${configuration.tier match
          case Tier.Small => "-Xms1g,-Xmx1g"
          case Tier.Scale => s"-Xms${configuration.heap},-Xmx${configuration.heap}"
        }",
      "benchmark.jvm.flags=-XX:+AlwaysPreTouch",
      s"hardware=${sys.env.getOrElse("FRAME4S_HARDWARE", "unrecorded")}",
      "scala.version=3.7.4",
      "sbt.version=1.10.5",
      "jmh.version=1.37",
      "sbt-jmh.version=0.4.8",
      "saddle.version=4.0.0-M14",
      "frame4s.backends=semantic-reference,columnar-candidate",
      "frame4s.columnar.fallback=forbidden-in-candidate-benchmarks",
      s"fused.scattered.seed.unsigned=${java.lang.Long.toUnsignedString(FusedPipelineFixture.Seed)}",
      s"fused.scattered.permutation.sha256=${FusedPipelineFixture.digest(scatteredIds)}"
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
    val preamble = configuration.tier match
      case Tier.Small =>
        """@The semantic reference interpreter is an executable oracle, not the optimized backend.
           @Every `ColumnarBenchmarks` row refuses fallback and must carry the same checksum and
           @output cardinality as its corresponding `ReferenceBenchmarks` row.
           @The materialized primitive projection, both fused filter/project fixtures, and
           @materialized nullable grouped-sum Saddle rows are comparable. The contiguous fused
           @fixture is retained for the ratified historical threshold; the deterministic scattered
           @fixture governs general-selection comparisons. The raw primitive scan and scalar
           @grouped reduction remain explicit lower bounds, not win/loss comparators. SQL
           @duplicate-key joins, dictionary layout, CSV acquisition, and owned-table construction
           @have no claimed Saddle-equivalent result. Scautable is intentionally excluded from
           @relational rankings."""
          .stripMargin('@')
      case Tier.Scale =>
        s"""@Scale tier at ${configuration.rows} rows. Only the columnar candidate runs.
            @
            @This tier has no reference oracle, and that is a stated limit rather than an
            @omission. The semantic reference join is a full nested-loop cross product, so
            @executing it here would require on the order of ${configuration.rows.toLong * configuration.rows.toLong}
            @predicate evaluations. Candidate agreement with the reference interpreter is
            @established by the cross-platform conformance laws and by the small tier, whose
            @receipts remain the ratified oracle record. Checksums below are candidate
            @self-consistency values: they detect drift between runs of this tier, and they
            @are not independent proof of semantic correctness.
            @
            @Saddle, the specialized-array lower bounds, and the reference rows are absent by
            @construction, so this receipt ranks nothing against them.""".stripMargin('@')

    val summary =
      s"""@# frame4s benchmark court receipt
         @
         @$mode
         @
         @$preamble
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
      "SaddleBenchmarks.filterProjectArithmetic" ->
        "ReferenceBenchmarks.fusedFilterProjectArithmetic",
      "SaddleBenchmarks.filterProjectArithmeticScattered" ->
        "ReferenceBenchmarks.fusedFilterProjectArithmeticScattered",
      "SpecializedArrayBenchmarks.fusedFilterProjectArithmetic" ->
        "ReferenceBenchmarks.fusedFilterProjectArithmetic",
      "SpecializedArrayBenchmarks.fusedFilterProjectArithmeticScattered" ->
        "ReferenceBenchmarks.fusedFilterProjectArithmeticScattered",
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
