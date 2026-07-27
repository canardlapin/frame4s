package frame4s.benchmarks

import org.openjdk.jmh.profile.GCProfiler
import org.openjdk.jmh.results.RunResult
import org.openjdk.jmh.results.format.ResultFormatType
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.{OptionsBuilder, TimeValue}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object IndexCourtRunner:
  private enum Phase:
    case Baseline
    case Admitted(baseline: Path)
    case Layouts(baseline: Path)
    case Extended(baseline: Path)

    def name: String = this match
      case Baseline    => "baseline"
      case Admitted(_) => "admitted"
      case Layouts(_)  => "layouts"
      case Extended(_) => "extended"

  final private case class Configuration(
      receipt: Path,
      phase: Phase,
      quick: Boolean
  )

  final private case class Validation(
      benchmark: String,
      rows: Int,
      outputRows: Int,
      checksum: String,
      status: String,
      layout: String = "control",
      shape: String = "unique"
  )

  final private case class BaselineScore(
      benchmark: String,
      rows: Int,
      milliseconds: Double
  )

  final private case class Comparison(
      workload: String,
      rows: Int,
      baselineMilliseconds: Double,
      indexedMilliseconds: Double,
      buildMilliseconds: Double,
      speedup: Double,
      breakEvenQueries: Option[Double],
      gate: String,
      passes: Boolean
  )

  private val BaselineRow =
    raw"""^\| `([^`]+)` \| ([0-9]+) \| avgt \| ([0-9.Ee+-]+) ms/op \|.*$$""".r
  private val AdmittedFastSingleOneMillionMilliseconds = 0.000015266
  private val R5fFastBatchOneMillionMilliseconds = 0.000384870
  private val R5fCompactBatchOneMillionMilliseconds = 0.00152138

  def main(arguments: Array[String]): Unit =
    val configuration = parse(arguments.toList)
    Files.createDirectories(configuration.receipt.resolve("raw"))
    val raw = configuration.receipt.resolve("raw/jmh.json")
    val log = configuration.receipt.resolve("raw/jmh.log")
    val include = configuration.phase match
      case Phase.Baseline =>
        "frame4s\\.benchmarks\\.IndexScanCourt\\..*"
      case Phase.Admitted(_) =>
        "frame4s\\.benchmarks\\.(IndexLookupCourt|IndexBuildCourt)\\..*"
      case Phase.Layouts(_) =>
        "frame4s\\.benchmarks\\.(IndexLookupCourt|IndexBuildCourt|CompactIndexLookupCourt|CompactIndexBuildCourt|IndexLegacyControlCourt)\\..*"
      case Phase.Extended(_) =>
        if configuration.quick then
          "frame4s\\.benchmarks\\.(ExtendedIndexLookupCourt|ExtendedIndexBuildCourt)\\..*"
        else
          "frame4s\\.benchmarks\\.(ExtendedIndexLookupCourt|ExtendedIndexBuildCourt|FlatAllEqualBuildCourt)\\..*"
    val builder = new OptionsBuilder()
      .include(include)
      .result(raw.toString)
      .resultFormat(ResultFormatType.JSON)
      .output(log.toString)
      .addProfiler(classOf[GCProfiler])

    configuration.phase match
      case Phase.Extended(_) =>
        val _ = builder.param("rows", "1000000")
        if configuration.quick then
          val _ = builder.param("shape", "unique")
      case _ => ()

    if configuration.quick then
      val _ = builder
        .warmupIterations(1)
        .warmupTime(TimeValue.milliseconds(100))
        .measurementIterations(1)
        .measurementTime(TimeValue.milliseconds(150))
        .forks(1)

    val validations = validate(configuration.phase, configuration.quick)
    writeEnvironment(configuration)
    writeValidations(configuration.receipt, validations)
    val results = new Runner(builder.build()).run().asScala.toVector
    sanitizeMachinePaths(raw, configuration.receipt)
    sanitizeMachinePaths(log, configuration.receipt)
    requireComplete(results, validations)
    val admitted = configuration.phase match
      case Phase.Baseline =>
        writeBaselineSummary(configuration, results, validations)
        true
      case Phase.Admitted(baseline) =>
        writeAdmittedSummary(configuration, baseline, results, validations)
      case Phase.Layouts(baseline) =>
        writeLayoutSummary(configuration, baseline, results, validations)
      case Phase.Extended(baseline) =>
        writeExtendedSummary(configuration, baseline, results, validations)
    if !admitted && !configuration.quick then
      throw new IllegalStateException(
        "secondary-index candidate missed one or more precommitted admission gates"
      )

  private def parse(arguments: List[String]): Configuration =
    def value(flag: String): Option[String] =
      arguments.sliding(2).collectFirst { case List(`flag`, actual) => actual }

    val phase = value("--phase").getOrElse("baseline") match
      case "baseline" => Phase.Baseline
      case "admitted" =>
        Phase.Admitted(
          Path.of:
            value("--baseline").getOrElse:
              throw new IllegalArgumentException(
                "--baseline <baseline-summary-directory> is required for admitted runs"
              )
        )
      case "layouts" =>
        Phase.Layouts(
          Path.of:
            value("--baseline").getOrElse:
              throw new IllegalArgumentException(
                "--baseline <baseline-summary-directory> is required for layout runs"
              )
        )
      case "extended" =>
        Phase.Extended(
          Path.of:
            value("--baseline").getOrElse:
              throw new IllegalArgumentException(
                "--baseline <baseline-summary-directory> is required for extended runs"
              )
        )
      case _ =>
        throw new IllegalArgumentException(
          "--phase must be baseline, admitted, layouts, or extended"
        )
    Configuration(
      Path.of:
        value("--receipt").getOrElse:
          throw new IllegalArgumentException("--receipt <directory> is required")
      ,
      phase,
      arguments.contains("--quick")
    )

  private def validate(phase: Phase, quick: Boolean): Vector[Validation] =
    phase match
      case Phase.Baseline =>
        Vector(100000, 1000000).flatMap: rows =>
          val state = new IndexScanState
          state.rows = rows
          state.setup()
          val court = new IndexScanCourt
          Vector(
            Validation(
              "IndexScanCourt.singleLookup",
              rows,
              1,
              java.lang.Long.toUnsignedString(court.singleLookup(state)),
              "linear-scan lower bound"
            ),
            Validation(
              "IndexScanCourt.batch32Lookup",
              rows,
              state.batchSize,
              java.lang.Long.toUnsignedString(court.batch32Lookup(state)),
              "linear-scan lower bound"
            )
          )
      case Phase.Admitted(_) =>
        Vector(100000, 1000000).flatMap: rows =>
          val state = new IndexLookupState
          state.rows = rows
          state.setup()
          try
            val scanSingle = state.scanSingle()
            val indexedSingle = state.indexedSingle()
            val scanBatch = state.scanBatch()
            val indexedBatch = state.indexedBatch()
            requireExact("single-key lookup", rows, scanSingle, indexedSingle)
            requireExact("32-key lookup", rows, scanBatch, indexedBatch)
            val court = new IndexLookupCourt
            Vector(
              Validation(
                "IndexLookupCourt.singleLookup",
                rows,
                indexedSingle.length,
                java.lang.Long.toUnsignedString(court.singleLookup(state)),
                "exact parity with stable linear scan"
              ),
              Validation(
                "IndexLookupCourt.batch32Lookup",
                rows,
                indexedBatch.length,
                java.lang.Long.toUnsignedString(court.batch32Lookup(state)),
                "exact parity with stable linear scan"
              ),
              Validation(
                "IndexBuildCourt.build",
                rows,
                rows,
                state.buildIndex().toString,
                "owned index bytes"
              )
            )
          finally state.tearDown()
      case Phase.Layouts(_) =>
        Vector(100000, 1000000).flatMap: rows =>
          validateLayout(rows, compact = false) ++ validateLayout(rows, compact = true)
      case Phase.Extended(_) =>
        val extended = validateExtended(if quick then Vector("unique")
        else
          Vector(
            "unique",
            "mixed-miss",
            "fanout-8",
            "skewed"
          ))
        if quick then extended else extended ++ validateFlatAllEqual()

  private def validateExtended(shapes: Vector[String]): Vector[Validation] =
    val layouts =
      Vector("fast-hash", "compact-sorted", "packed-sorted", "flat-hash-rows")
    layouts.flatMap: layout =>
      shapes.flatMap: shape =>
        val state = new ExtendedIndexLookupState
        state.rows = 1000000
        state.layoutName = layout
        state.shape = shape
        state.setup()
        try
          val scanSingle = state.scanSingle()
          val indexedSingle = state.indexedSingle()
          val scanBatch = state.scanBatch()
          val queryBefore = state.batchTargets.clone()
          val indexedBatch = state.indexedBatch()
          requireExact(s"$shape single-key lookup", state.rows, scanSingle, indexedSingle)
          requireExact(s"$shape batch lookup", state.rows, scanBatch, indexedBatch)
          if !queryBefore.sameElements(state.batchTargets) then
            throw new IllegalStateException(
              s"$layout $shape lookup mutated caller-owned batch keys"
            )
          Vector(
            Validation(
              "ExtendedIndexLookupCourt.singleLookup",
              state.rows,
              indexedSingle.length,
              java.lang.Long.toUnsignedString(indexedSingle.checksum),
              "exact parity with stable linear scan",
              layout,
              shape
            ),
            Validation(
              "ExtendedIndexLookupCourt.batch32Lookup",
              state.rows,
              indexedBatch.length,
              java.lang.Long.toUnsignedString(indexedBatch.checksum),
              "exact parity with stable linear scan; query unchanged",
              layout,
              shape
            ),
            Validation(
              "ExtendedIndexBuildCourt.build",
              state.rows,
              state.rows,
              state.buildIndex().toString,
              "owned index bytes",
              layout,
              shape
            )
          )
        finally state.tearDown()

  private def validateFlatAllEqual(): Vector[Validation] =
    val state = new FlatAllEqualBuildState
    state.rows = 1000000
    state.layoutName = "flat-hash-rows"
    state.shape = "all-equal"
    state.setup()
    try
      requireExact(
        "all-equal single-key lookup",
        state.rows,
        state.scanSingle(),
        state.indexedSingle()
      )
      Vector(
        Validation(
          "FlatAllEqualBuildCourt.build",
          state.rows,
          state.rows,
          state.buildIndex().toString,
          "all-equal build; exact lookup parity with stable scan",
          state.layoutName,
          state.shape
        )
      )
    finally state.tearDown()

  private def validateLayout(rows: Int, compact: Boolean): Vector[Validation] =
    val state =
      if compact then new CompactIndexLookupState
      else new IndexLookupState
    state.rows = rows
    state.setup()
    try
      val scanSingle = state.scanSingle()
      val indexedSingle = state.indexedSingle()
      val scanBatch = state.scanBatch()
      val indexedBatch = state.indexedBatch()
      requireExact("single-key lookup", rows, scanSingle, indexedSingle)
      requireExact("32-key lookup", rows, scanBatch, indexedBatch)
      val lookupName =
        if compact then "CompactIndexLookupCourt" else "IndexLookupCourt"
      val buildName =
        if compact then "CompactIndexBuildCourt" else "IndexBuildCourt"
      val validations = Vector(
        Validation(
          s"$lookupName.singleLookup",
          rows,
          indexedSingle.length,
          java.lang.Long.toUnsignedString(indexedSingle.checksum),
          "exact parity with stable linear scan"
        ),
        Validation(
          s"$lookupName.batch32Lookup",
          rows,
          indexedBatch.length,
          java.lang.Long.toUnsignedString(indexedBatch.checksum),
          "exact parity with stable linear scan"
        ),
        Validation(
          s"$buildName.build",
          rows,
          rows,
          state.buildIndex().toString,
          "owned index bytes"
        )
      )
      if compact then validations
      else
        validations :+ Validation(
          "IndexLegacyControlCourt.singleLookup",
          rows,
          indexedSingle.length,
          java.lang.Long.toUnsignedString(
            state.indexedBatchCompatibleSingle().checksum
          ),
          "exact former batch-compatible one-key path"
        )
    finally state.tearDown()

  private def requireExact(
      workload: String,
      rows: Int,
      scan: Int32LookupResult,
      indexed: Int32LookupResult
  ): Unit =
    val same =
      scan.length == indexed.length &&
        scan.values
          .take(scan.length)
          .sameElements(indexed.values.take(indexed.length))
    if !same then
      throw new IllegalStateException(
        s"$workload at $rows rows differs between scan and index"
      )

  private def writeEnvironment(configuration: Configuration): Unit =
    val runtime = Runtime.getRuntime
    val (indexDescription, baselineDescription) = configuration.phase match
      case Phase.Baseline =>
        "absent" -> "none"
      case Phase.Admitted(baseline) =>
        "plain-int32" -> displayPath(baseline)
      case Phase.Layouts(baseline) =>
        "fast-hash,compact-sorted" -> displayPath(baseline)
      case Phase.Extended(baseline) =>
        "fast-hash,compact-sorted,packed-sorted,flat-hash-rows" ->
          displayPath(baseline)
    val properties = Vector(
      "receipt_format=1",
      "suite=frame4s-secondary-index-court",
      s"phase=${configuration.phase.name}",
      s"quick=${configuration.quick}",
      "rows=100000,1000000",
      "query.keys=1,32",
      s"query.shapes=${configuration.phase match
          case Phase.Extended(_) => "unique,mixed-miss,fanout-8,skewed"
          case _                 => "unique"
        }",
      "source.order=stable-unsorted",
      s"index=$indexDescription",
      s"baseline=$baselineDescription",
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
      "sbt-jmh.version=0.4.8"
    )
    write(
      configuration.receipt.resolve("environment.properties"),
      properties.mkString("", "\n", "\n")
    )

  private def writeValidations(receipt: Path, validations: Vector[Validation]): Unit =
    val lines = validations.map: item =>
      Vector(
        item.benchmark,
        item.rows.toString,
        item.outputRows.toString,
        item.checksum,
        item.status,
        item.layout,
        item.shape
      ).mkString("\t")
    write(
      receipt.resolve("validation.tsv"),
      (
        "benchmark\tinput_rows\toutput_rows\tchecksum_or_bytes\tstatus\tlayout\tshape" +: lines
      ).mkString("", "\n", "\n")
    )

  private def writeBaselineSummary(
      configuration: Configuration,
      results: Vector[RunResult],
      validations: Vector[Validation]
  ): Unit =
    val mode =
      if configuration.quick then "Quick wiring receipt; timings are provisional."
      else "Full pre-implementation lower-bound receipt."
    val summary =
      s"""@# frame4s secondary-index baseline
         @
         @$mode
         @
         @Both workloads scan the same stable unsorted `Int32` values and materialize
         @the selected values before checksumming. Query-key construction is outside
         @the timed method. No secondary index exists in this phase.
         @
         @${resultTable(results, validations)}
         @
         @Raw JMH output is under `raw/`; `validation.tsv` and
         @`environment.properties` carry semantic and runtime provenance.
         @""".stripMargin('@')
    write(configuration.receipt.resolve("summary.md"), summary)

  private def writeAdmittedSummary(
      configuration: Configuration,
      baselineDirectory: Path,
      results: Vector[RunResult],
      validations: Vector[Validation]
  ): Boolean =
    val baseline = readBaseline(baselineDirectory)
    val comparisons = Vector(
      comparison(
        "single",
        100000,
        baseline,
        results,
        validations,
        minimum = None
      ),
      comparison(
        "single",
        1000000,
        baseline,
        results,
        validations,
        minimum = Some(2.0)
      ),
      comparison(
        "batch32",
        100000,
        baseline,
        results,
        validations,
        minimum = Some(2.0)
      ),
      comparison(
        "batch32",
        1000000,
        baseline,
        results,
        validations,
        minimum = Some(5.0)
      )
    )
    val memory = validations
      .filter(_.benchmark == "IndexBuildCourt.build")
      .map(value => value.rows -> value.checksum.toLong)
      .toMap
    val memoryPasses = memory(1000000).toDouble / 1000000.0 <= 24.0
    val admitted = comparisons.forall(_.passes) && memoryPasses
    val comparisonRows = comparisons.map: item =>
      val breakEven = item.breakEvenQueries.fold("never")(value => f"$value%.2f")
      f"| ${item.workload} | ${item.rows} | ${item.baselineMilliseconds}%.6f | ${item.indexedMilliseconds}%.6f | ${item.speedup}%.2fx | ${item.buildMilliseconds}%.6f | $breakEven | ${item.gate} | ${status(item.passes)} |"
    val memoryRows = memory.toVector
      .sortBy(_._1)
      .map: (rows, bytes) =>
        val bytesPerRow = bytes.toDouble / rows.toDouble
        val passes = rows != 1000000 || bytesPerRow <= 24.0
        f"| $rows | $bytes | $bytesPerRow%.3f | ${
            if rows == 1000000 then "<= 24.000" else "reported"
          } | ${status(passes)} |"
    val mode =
      if configuration.quick then
        "Quick wiring receipt; timings and the displayed gate decision are provisional."
      else "Full admission receipt."
    val summary =
      s"""@# frame4s secondary-index admission
         @
         @$mode The candidate ${
           if admitted then "passes" else "does not pass"
         } the
         @precommitted direct-lookup gates. Exact values and source order were checked
         @against the linear scan before timing.
         @
         @## Warm lookup and cold construction
         @
         @| Workload | Rows | Frozen scan ms/op | Indexed ms/op | Speedup | Build ms | Break-even queries | Gate | Result |
         @|---|---:|---:|---:|---:|---:|---:|---:|---:|
         @${comparisonRows.mkString("\n")}
         @
         @Break-even is `build_ms / (scan_ms - indexed_ms)` and therefore includes
         @index construction rather than presenting warm lookup alone.
         @
         @## Owned memory
         @
         @| Rows | Owned bytes | Bytes/row | Gate | Result |
         @|---|---:|---:|---:|---:|
         @${memoryRows.mkString("\n")}
         @
         @## Raw candidate measurements
         @
         @${resultTable(results, validations)}
         @
         @Raw JMH output is under `raw/`; `validation.tsv` and
         @`environment.properties` carry semantic and runtime provenance. Baseline
         @scores come from `${displayPath(baselineDirectory.resolve("summary.md"))}`.
         @""".stripMargin('@')
    write(configuration.receipt.resolve("summary.md"), summary)
    admitted

  private def writeLayoutSummary(
      configuration: Configuration,
      baselineDirectory: Path,
      results: Vector[RunResult],
      validations: Vector[Validation]
  ): Boolean =
    val baseline = readBaseline(baselineDirectory)
    val fast = layoutComparisons(
      baseline,
      results,
      validations,
      "IndexLookupCourt",
      "IndexBuildCourt"
    )
    val compact = layoutComparisons(
      baseline,
      results,
      validations,
      "CompactIndexLookupCourt",
      "CompactIndexBuildCourt"
    )
    val fastMemory = ownedMemory(validations, "IndexBuildCourt.build")
    val compactMemory = ownedMemory(validations, "CompactIndexBuildCourt.build")
    val compactBytesPerRow =
      compactMemory(1000000).toDouble / 1000000.0
    val memoryReduction =
      1.0 - compactMemory(1000000).toDouble / fastMemory(1000000).toDouble
    val memoryPasses =
      compactBytesPerRow <= 12.0 && memoryReduction >= 0.40
    val compactSpeedPasses = compact.forall(_.passes)
    val fastSingleOneMillion =
      averageScore(results, "IndexLookupCourt.singleLookup", 1000000)
    val legacySingleOneMillion =
      averageScore(results, "IndexLegacyControlCourt.singleLookup", 1000000)
    val fastPathSpeedup = legacySingleOneMillion / fastSingleOneMillion
    val fastAllocation =
      normalizedAllocation(results, "IndexLookupCourt.singleLookup", 1000000)
    val legacyAllocation =
      normalizedAllocation(
        results,
        "IndexLegacyControlCourt.singleLookup",
        1000000
      )
    val fastPathPasses =
      fastPathSpeedup >= 1.25 && fastAllocation <= 72.0
    val admitted = memoryPasses && compactSpeedPasses && fastPathPasses

    val comparisonRows =
      layoutComparisonRows("FastHash", fast) ++
        layoutComparisonRows("CompactSorted", compact)
    val memoryRows = Vector(100000, 1000000).flatMap: rows =>
      val fastBytes = fastMemory(rows)
      val compactBytes = compactMemory(rows)
      val fastPerRow = fastBytes.toDouble / rows.toDouble
      val compactPerRow = compactBytes.toDouble / rows.toDouble
      val reduction = 1.0 - compactBytes.toDouble / fastBytes.toDouble
      val passes =
        rows != 1000000 || (compactPerRow <= 12.0 && reduction >= 0.40)
      Vector(
        f"| FastHash | $rows | $fastBytes | $fastPerRow%.3f | control | reported |",
        f"| CompactSorted | $rows | $compactBytes | $compactPerRow%.3f | ${reduction * 100.0}%.2f%% | ${status(passes)} |"
      )
    val mode =
      if configuration.quick then
        "Quick wiring receipt; timings and gate decisions are provisional."
      else "Full R5f layout admission receipt."
    val summary =
      s"""@# frame4s secondary-index layout court
         @
         @$mode The explicit compact layout ${
           if admitted then "passes" else "does not pass"
         } the
         @precommitted speed, memory, and fast-path gates. Both layouts were checked
         @against the same frozen stable scan before timing.
         @
         @## Warm lookup and cold construction
         @
         @| Layout | Workload | Rows | Frozen scan ms/op | Index ms/op | Speedup | Build ms | Break-even queries | Gate | Result |
         @|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
         @${comparisonRows.mkString("\n")}
         @
         @Break-even is `build_ms / (scan_ms - index_ms)`. It includes index
         @construction and is reported separately for each layout.
         @
         @## Owned memory
         @
         @| Layout | Rows | Owned bytes | Bytes/source row | Reduction versus FastHash | Result |
         @|---|---:|---:|---:|---:|---:|
         @${memoryRows.mkString("\n")}
         @
         @At 1,000,000 rows `CompactSorted` must use at most 12 bytes/source row
         @and at least 40% less owned memory than `FastHash`.
         @
         @## FastHash single-key guard
         @
         @| Measure | Result | Gate | Status |
         @|---|---:|---:|---:|
         @| Direct one-key latency | ${f"$fastSingleOneMillion%.9f"} ms/op | >= 1.25x faster than same-run former path | ${status(
           fastPathSpeedup >= 1.25
         )} |
         @| Former batch-compatible latency | ${f"$legacySingleOneMillion%.9f"} ms/op | same-run control (${f"$fastPathSpeedup%.2f"}x direct speedup) | reported |
         @| Normalized allocation | ${f"$fastAllocation%.3f"} B/op | <= 72.000 B/op | ${status(
           fastAllocation <= 72.0
         )} |
         @| Former-path allocation | ${f"$legacyAllocation%.3f"} B/op | same-run control | reported |
         @| Prior R5e direct point | ${f"$AdmittedFastSingleOneMillionMilliseconds%.9f"} ms/op | descriptive only; different JVM | reported |
         @
         @## Raw candidate measurements
         @
         @${resultTable(results, validations)}
         @
         @Raw JMH output is under `raw/`; `validation.tsv` and
         @`environment.properties` carry semantic and runtime provenance. Baseline
         @scores come from `${displayPath(baselineDirectory.resolve("summary.md"))}`.
         @""".stripMargin('@')
    write(configuration.receipt.resolve("summary.md"), summary)
    admitted

  private def writeExtendedSummary(
      configuration: Configuration,
      baselineDirectory: Path,
      results: Vector[RunResult],
      validations: Vector[Validation]
  ): Boolean =
    val rows = 1000000
    val compactBytes = extendedOwnedBytes(validations, "compact-sorted", "unique")
    val packedBytes = extendedOwnedBytes(validations, "packed-sorted", "unique")
    val flatBytes = extendedOwnedBytes(validations, "flat-hash-rows", "unique")
    val packedBytesPerRow = packedBytes.toDouble / rows.toDouble
    val flatBytesPerRow = flatBytes.toDouble / rows.toDouble
    val packedReduction = 1.0 - packedBytes.toDouble / compactBytes.toDouble
    val packedMemoryPasses =
      packedBytesPerRow <= 6.75 && packedReduction >= 0.15
    val flatMemoryPasses = flatBytesPerRow <= 12.0

    val compactSingle =
      extendedAverageScore(results, "singleLookup", "compact-sorted", "unique")
    val compactBatch =
      extendedAverageScore(results, "batch32Lookup", "compact-sorted", "unique")
    val flatSingle =
      extendedAverageScore(results, "singleLookup", "flat-hash-rows", "unique")
    val flatBatch =
      extendedAverageScore(results, "batch32Lookup", "flat-hash-rows", "unique")
    val flatSingleSpeedup = compactSingle / flatSingle
    val flatBatchSpeedup = compactBatch / flatBatch
    val flatSpeedPasses = flatSingleSpeedup >= 1.25 && flatBatchSpeedup >= 1.25
    val skewRows =
      if configuration.quick then ""
      else
        val compactSkewSingle =
          extendedAverageScore(results, "singleLookup", "compact-sorted", "skewed")
        val compactSkewBatch =
          extendedAverageScore(results, "batch32Lookup", "compact-sorted", "skewed")
        val flatSkewSingle =
          extendedAverageScore(results, "singleLookup", "flat-hash-rows", "skewed")
        val flatSkewBatch =
          extendedAverageScore(results, "batch32Lookup", "flat-hash-rows", "skewed")
        s"""@| Flat skewed single ratio versus compact | ${f"${compactSkewSingle / flatSkewSingle}%.3f"}x | reported loss/win | reported |
           @| Flat skewed batch32 ratio versus compact | ${f"${compactSkewBatch / flatSkewBatch}%.3f"}x | reported loss/win | reported |"""
          .stripMargin(
            '@'
          )

    val fastBatch =
      extendedAverageScore(results, "batch32Lookup", "fast-hash", "unique")
    val fastAllocation =
      extendedNormalizedAllocation(results, "batch32Lookup", "fast-hash", "unique")
    val compactAllocation =
      extendedNormalizedAllocation(
        results,
        "batch32Lookup",
        "compact-sorted",
        "unique"
      )
    val batchAllocationPasses =
      fastAllocation <= 500.0 && compactAllocation <= 500.0
    val frozenLatencyPasses =
      fastBatch <= R5fFastBatchOneMillionMilliseconds * 1.10 &&
        compactBatch <= R5fCompactBatchOneMillionMilliseconds * 1.10

    val packedDecision =
      if packedMemoryPasses then "admitted as the lower-memory sorted layout"
      else "not admitted"
    val batchDecision =
      if batchAllocationPasses && frozenLatencyPasses then "admitted as the batch lookup path"
      else "not admitted"
    val flatDecision =
      if flatMemoryPasses && flatSpeedPasses then
        "admitted as an explicit low-fanout balanced layout; not as a skewed-key layout"
      else "not admitted as the low-fanout balanced layout"
    val mode =
      if configuration.quick then
        "Quick wiring receipt; timings and gate decisions are provisional."
      else "Full R5g extended-layout receipt."
    val validationScope =
      if configuration.quick then
        "All four layouts passed exact stable-scan validation on the 1M-row unique control."
      else "All four layouts and all four query shapes passed exact stable-scan validation."
    val summary =
      s"""@# frame4s R5g extended secondary-index court
         @
         @$mode $validationScope Candidate losses remain visible below.
         @
         @## Decisions
         @
         @- `PackedSorted` is **$packedDecision**.
         @- The clone-once/token-reuse batch engine is **$batchDecision**.
         @- `FlatHashRows` is **$flatDecision**.
         @- `FastHash` remains the default; this court does not infer an automatic
         @  layout policy.
         @
         @## Precommitted gates
         @
         @| Gate | Result | Threshold | Status |
         @|---|---:|---:|---:|
         @| Packed owned bytes/source row | ${f"$packedBytesPerRow%.3f"} | <= 6.750 | ${status(
           packedBytesPerRow <= 6.75
         )} |
         @| Packed reduction versus compact | ${f"${packedReduction * 100.0}%.2f"}% | >= 15.00% | ${status(
           packedReduction >= 0.15
         )} |
         @| FastHash batch32 allocation | ${f"$fastAllocation%.3f"} B/op | <= 500.000 | ${status(
           fastAllocation <= 500.0
         )} |
         @| CompactSorted batch32 allocation | ${f"$compactAllocation%.3f"} B/op | <= 500.000 | ${status(
           compactAllocation <= 500.0
         )} |
         @| FastHash batch32 frozen-point ratio | ${f"${fastBatch / R5fFastBatchOneMillionMilliseconds}%.3f"}x | <= 1.100x | ${status(
           fastBatch <= R5fFastBatchOneMillionMilliseconds * 1.10
         )} |
         @| CompactSorted batch32 frozen-point ratio | ${f"${compactBatch / R5fCompactBatchOneMillionMilliseconds}%.3f"}x | <= 1.100x | ${status(
           compactBatch <= R5fCompactBatchOneMillionMilliseconds * 1.10
         )} |
         @| Flat owned bytes/source row | ${f"$flatBytesPerRow%.3f"} | <= 12.000 | ${status(
           flatMemoryPasses
         )} |
         @| Flat unique single speedup versus compact | ${f"$flatSingleSpeedup%.3f"}x | >= 1.250x | ${status(
           flatSingleSpeedup >= 1.25
         )} |
         @| Flat unique batch32 speedup versus compact | ${f"$flatBatchSpeedup%.3f"}x | >= 1.250x | ${status(
           flatBatchSpeedup >= 1.25
         )} |
         @$skewRows
         @
         @The two frozen-point ratios are conservative cross-run guardrails. Same-run
         @layout rankings come from this receipt; a host/JDK difference is not
         @presented as a causal code regression. Ratios below 1.0 on the two skew
         @rows mean `CompactSorted` is faster; those losses limit the flat layout's
         @admission even though they do not rewrite the precommitted unique gates.
         @
         @## Full shape matrix
         @
         @${resultTable(results, validations)}
         @
         @`owned bytes` are retained arrays returned by each build. JMH allocation
         @for `ExtendedIndexBuildCourt.build` includes temporary radix arrays and
         @packing. Raw JMH output is under `raw/`; `validation.tsv` records layout,
         @shape, output cardinality, and checksum. The frozen scan provenance is
         @`${displayPath(baselineDirectory.resolve("summary.md"))}`.
         @""".stripMargin('@')
    write(configuration.receipt.resolve("summary.md"), summary)
    true

  private def layoutComparisons(
      baseline: Vector[BaselineScore],
      results: Vector[RunResult],
      validations: Vector[Validation],
      lookupCourt: String,
      buildCourt: String
  ): Vector[Comparison] =
    Vector(
      comparison(
        "single",
        100000,
        baseline,
        results,
        validations,
        minimum = None,
        lookupCourt = lookupCourt,
        buildCourt = buildCourt
      ),
      comparison(
        "single",
        1000000,
        baseline,
        results,
        validations,
        minimum = if lookupCourt.startsWith("Compact") then Some(2.0) else None,
        lookupCourt = lookupCourt,
        buildCourt = buildCourt
      ),
      comparison(
        "batch32",
        100000,
        baseline,
        results,
        validations,
        minimum = if lookupCourt.startsWith("Compact") then Some(2.0) else None,
        lookupCourt = lookupCourt,
        buildCourt = buildCourt
      ),
      comparison(
        "batch32",
        1000000,
        baseline,
        results,
        validations,
        minimum = if lookupCourt.startsWith("Compact") then Some(5.0) else None,
        lookupCourt = lookupCourt,
        buildCourt = buildCourt
      )
    )

  private def layoutComparisonRows(
      layout: String,
      comparisons: Vector[Comparison]
  ): Vector[String] =
    comparisons.map: item =>
      val breakEven = item.breakEvenQueries.fold("never")(value => f"$value%.2f")
      f"| $layout | ${item.workload} | ${item.rows} | ${item.baselineMilliseconds}%.6f | ${item.indexedMilliseconds}%.6f | ${item.speedup}%.2fx | ${item.buildMilliseconds}%.6f | $breakEven | ${item.gate} | ${status(item.passes)} |"

  private def ownedMemory(
      validations: Vector[Validation],
      benchmark: String
  ): Map[Int, Long] =
    validations
      .filter(_.benchmark == benchmark)
      .map(value => value.rows -> value.checksum.toLong)
      .toMap

  private def comparison(
      workload: String,
      rows: Int,
      baseline: Vector[BaselineScore],
      results: Vector[RunResult],
      validations: Vector[Validation],
      minimum: Option[Double],
      lookupCourt: String = "IndexLookupCourt",
      buildCourt: String = "IndexBuildCourt"
  ): Comparison =
    val method = if workload == "single" then "singleLookup" else "batch32Lookup"
    val scan = baseline
      .find(value => value.benchmark == s"IndexScanCourt.$method" && value.rows == rows)
      .getOrElse:
        throw new IllegalStateException(
          s"baseline omits IndexScanCourt.$method at $rows rows"
        )
      .milliseconds
    val indexed = averageScore(results, s"$lookupCourt.$method", rows)
    val build = averageScore(results, s"$buildCourt.build", rows)
    val speedup = scan / indexed
    val breakEven =
      if scan > indexed then Some(build / (scan - indexed))
      else None
    val gate = minimum.fold("reported")(value => f">= $value%.2fx")
    Comparison(
      workload,
      rows,
      scan,
      indexed,
      build,
      speedup,
      breakEven,
      gate,
      minimum.forall(speedup >= _)
    )

  private def readBaseline(directory: Path): Vector[BaselineScore] =
    val summary = directory.resolve("summary.md")
    if !Files.isRegularFile(summary) then
      throw new IllegalStateException(s"baseline summary does not exist: $summary")
    Files
      .readAllLines(summary, StandardCharsets.UTF_8)
      .asScala
      .collect:
        case BaselineRow(benchmark, rows, milliseconds) =>
          BaselineScore(benchmark, rows.toInt, milliseconds.toDouble)
      .toVector

  private def averageScore(
      results: Vector[RunResult],
      benchmark: String,
      rows: Int
  ): Double =
    results
      .find: result =>
        val params = result.getParams
        shortBenchmark(params.getBenchmark) == benchmark &&
        params.getParam("rows").toInt == rows &&
        params.getMode.shortLabel() == "avgt"
      .getOrElse:
        throw new IllegalStateException(
          s"JMH result omits $benchmark at $rows rows in average-time mode"
        )
      .getPrimaryResult
      .getScore

  private def extendedAverageScore(
      results: Vector[RunResult],
      method: String,
      layout: String,
      shape: String
  ): Double =
    results
      .find: result =>
        val params = result.getParams
        shortBenchmark(params.getBenchmark) == s"ExtendedIndexLookupCourt.$method" &&
        params.getParam("rows").toInt == 1000000 &&
        params.getParam("layoutName") == layout &&
        params.getParam("shape") == shape &&
        params.getMode.shortLabel() == "avgt"
      .getOrElse:
        throw new IllegalStateException(
          s"extended court omits $method layout=$layout shape=$shape"
        )
      .getPrimaryResult
      .getScore

  private def extendedOwnedBytes(
      validations: Vector[Validation],
      layout: String,
      shape: String
  ): Long =
    validations
      .find: validation =>
        validation.benchmark == "ExtendedIndexBuildCourt.build" &&
          validation.rows == 1000000 &&
          validation.layout == layout &&
          validation.shape == shape
      .getOrElse:
        throw new IllegalStateException(
          s"extended validation omits owned bytes layout=$layout shape=$shape"
        )
      .checksum
      .toLong

  private def normalizedAllocation(
      results: Vector[RunResult],
      benchmark: String,
      rows: Int
  ): Double =
    results
      .find: result =>
        val params = result.getParams
        shortBenchmark(params.getBenchmark) == benchmark &&
        params.getParam("rows").toInt == rows &&
        params.getMode.shortLabel() == "avgt"
      .flatMap: result =>
        result.getSecondaryResults.asScala
          .get("gc.alloc.rate.norm")
          .map(_.getScore)
      .getOrElse:
        throw new IllegalStateException(
          s"JMH result omits normalized allocation for $benchmark at $rows rows"
        )

  private def extendedNormalizedAllocation(
      results: Vector[RunResult],
      method: String,
      layout: String,
      shape: String
  ): Double =
    results
      .find: result =>
        val params = result.getParams
        shortBenchmark(params.getBenchmark) == s"ExtendedIndexLookupCourt.$method" &&
        params.getParam("rows").toInt == 1000000 &&
        params.getParam("layoutName") == layout &&
        params.getParam("shape") == shape &&
        params.getMode.shortLabel() == "avgt"
      .flatMap: result =>
        result.getSecondaryResults.asScala
          .get("gc.alloc.rate.norm")
          .map(_.getScore)
      .getOrElse:
        throw new IllegalStateException(
          s"extended court omits allocation $method layout=$layout shape=$shape"
        )

  private def resultTable(
      results: Vector[RunResult],
      validations: Vector[Validation]
  ): String =
    val validationByKey =
      validations
        .map: value =>
          (value.benchmark, value.rows, value.layout, value.shape) -> value
        .toMap
    val rows = results
      .sortBy: result =>
        val params = result.getParams
        (params.getBenchmark, params.getParam("rows"), params.getMode.shortLabel())
      .map: result =>
        val params = result.getParams
        val benchmark = shortBenchmark(params.getBenchmark)
        val inputRows = params.getParam("rows").toInt
        val layout = parameter(params, "layoutName", "control")
        val shape = parameter(params, "shape", "unique")
        val primary = result.getPrimaryResult
        val allocation = result.getSecondaryResults.asScala
          .get("gc.alloc.rate.norm")
          .map(value => f"${value.getScore}%.3f ${value.getScoreUnit}")
          .getOrElse("not reported")
        val validation = validationByKey((benchmark, inputRows, layout, shape))
        val score = f"${primary.getScore}%.6g ${primary.getScoreUnit}"
        s"| `$benchmark` | $inputRows | ${params.getMode.shortLabel()} | $score | $allocation | ${validation.outputRows} | `${validation.checksum}` | $layout | $shape |"
    (
      Vector(
        "| Benchmark | Input rows | Mode | Time or throughput | Allocation | Output rows | Checksum or owned bytes | Layout | Shape |",
        "|---|---:|---:|---:|---:|---:|---:|---|---|"
      ) ++ rows
    ).mkString("\n")

  private def requireComplete(
      results: Vector[RunResult],
      validations: Vector[Validation]
  ): Unit =
    val measured = results.iterator.map: result =>
      val params = result.getParams
      (
        shortBenchmark(params.getBenchmark),
        params.getParam("rows").toInt,
        parameter(params, "layoutName", "control"),
        parameter(params, "shape", "unique")
      )
    val missing =
      validations
        .map(value => (value.benchmark, value.rows, value.layout, value.shape))
        .toSet -- measured.toSet
    if missing.nonEmpty then
      throw new IllegalStateException(
        s"index court omitted validated benchmarks: ${missing.toVector.sorted.mkString(", ")}"
      )

  private def shortBenchmark(benchmark: String): String =
    benchmark.split('.').takeRight(2).mkString(".")

  private def parameter(
      params: org.openjdk.jmh.infra.BenchmarkParams,
      name: String,
      default: String
  ): String =
    if params.getParamsKeys.contains(name) then params.getParam(name)
    else default

  private def status(passes: Boolean): String =
    if passes then "pass" else "fail"

  private def displayPath(path: Path): String =
    val absolute = path.toAbsolutePath.normalize
    val workspace = Path.of(System.getProperty("user.dir")).toAbsolutePath.normalize
    if absolute.startsWith(workspace) then workspace.relativize(absolute).toString
    else
      Option(path.getParent)
        .map(parent => parent.getFileName.resolve(path.getFileName).toString)
        .getOrElse(path.getFileName.toString)

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
