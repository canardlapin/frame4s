package frame4s.benchmarks

import frame4s.*
import org.openjdk.jmh.profile.GCProfiler
import org.openjdk.jmh.results.RunResult
import org.openjdk.jmh.results.format.ResultFormatType
import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.{OptionsBuilder, TimeValue}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

object PracticalPipelineCourtRunner:
  final private case class Configuration(
      receipt: Path,
      quick: Boolean,
      rows: Int,
      validateOnly: Boolean
  )

  final private case class Validation(
      benchmark: String,
      backend: String,
      outputRows: Long,
      checksum: String,
      structureChecksum: String,
      floatSignature: String,
      physicalPlan: String
  )

  final private case class FloatEvidence(
      count: Long,
      sum: Double,
      sumSquares: Double,
      minimum: Double,
      maximum: Double,
      rowWeightedSum: Double,
      rowWeightedSumSquares: Double
  ):
    def add(value: Double, row: Long): FloatEvidence =
      val weight = row.toDouble + 1.0
      FloatEvidence(
        count + 1L,
        sum + value,
        sumSquares + value * value,
        math.min(minimum, value),
        math.max(maximum, value),
        rowWeightedSum + weight * value,
        rowWeightedSumSquares + weight * value * value
      )

    def render(index: Int): String =
      Vector(
        index.toString,
        count.toString,
        java.lang.Double.toString(sum),
        java.lang.Double.toString(sumSquares),
        java.lang.Double.toString(minimum),
        java.lang.Double.toString(maximum),
        java.lang.Double.toString(rowWeightedSum),
        java.lang.Double.toString(rowWeightedSumSquares)
      ).mkString(":")

  def main(arguments: Array[String]): Unit =
    val configuration = parse(arguments.toList)
    Files.createDirectories(configuration.receipt.resolve("raw"))
    val validations = validate(configuration.rows)
    requireParity(validations)
    writeEnvironment(configuration)
    writeValidations(configuration.receipt, validations)
    if !configuration.validateOnly then
      val raw = configuration.receipt.resolve("raw/jmh.json")
      val log = configuration.receipt.resolve("raw/jmh.log")
      val builder = new OptionsBuilder()
        .include(
          "frame4s\\.benchmarks\\.PracticalPipeline(Reference|Columnar)Court\\..*"
        )
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
      val results = new Runner(builder.build()).run().asScala.toVector
      sanitizeMachinePaths(raw, configuration.receipt)
      sanitizeMachinePaths(log, configuration.receipt)
      requireComplete(results, validations)
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
    val state = new PracticalPipelineState
    state.rows = rows
    state.setup()
    try
      val referenceDerived = referenceObservation(
        "PracticalPipelineReferenceCourt.filterWithColumnsSelect",
        state.derived,
        state.sources
      )
      val referenceGrouped = referenceObservation(
        "PracticalPipelineReferenceCourt.selectGroupSummarise",
        state.grouped,
        state.sources
      )
      val columnarDerived = columnarObservation(
        "PracticalPipelineColumnarCourt.filterWithColumnsSelect",
        state.columnarDerived
      )
      val columnarGrouped = columnarObservation(
        "PracticalPipelineColumnarCourt.selectGroupSummarise",
        state.columnarGrouped
      )
      Vector(referenceDerived, referenceGrouped, columnarDerived, columnarGrouped)
    finally state.tearDown()

  private def referenceObservation[S <: scala.NamedTuple.AnyNamedTuple](
      benchmark: String,
      frame: Frame[S],
      sources: ReferenceSources
  )(using SchemaDescriptor[S]): Validation =
    val table = BenchmarkSupport.collect(frame, sources)
    try
      Validation(
        benchmark,
        "semantic-reference",
        table.rowCount,
        java.lang.Long.toUnsignedString(BenchmarkSupport.checksum(table)),
        java.lang.Long.toUnsignedString(structureChecksum(table)),
        floatSignature(table),
        "ReferenceExecution"
      )
    finally table.close()

  private def columnarObservation(
      benchmark: String,
      execution: ColumnarExecution
  ): Validation =
    val run = execution.run()
    if run.receipt.fallback.nonEmpty then
      throw new IllegalStateException(
        s"$benchmark unexpectedly fell back: ${run.receipt.fallback.get}"
      )
    val result = run.result.fold(error => throw new IllegalStateException(error.message), identity)
    try
      Validation(
        benchmark,
        "columnar-candidate",
        result.rowCount,
        java.lang.Long.toUnsignedString(
          result.checksum.fold(error => throw new IllegalStateException(error.message), identity)
        ),
        java.lang.Long.toUnsignedString(structureChecksum(result)),
        floatSignature(result),
        run.receipt.physicalPlan
      )
    finally result.close()

  private def requireParity(validations: Vector[Validation]): Unit =
    val byName = validations.map(value => value.benchmark -> value).toMap
    val methods = Vector("filterWithColumnsSelect", "selectGroupSummarise")
    val mismatches = methods.flatMap: method =>
      val reference = byName(s"PracticalPipelineReferenceCourt.$method")
      val candidate = byName(s"PracticalPipelineColumnarCourt.$method")
      Option.when(
        reference.outputRows != candidate.outputRows ||
          reference.checksum != candidate.checksum ||
          reference.structureChecksum != candidate.structureChecksum ||
          reference.floatSignature != candidate.floatSignature
      ):
        s"$method reference=${reference.outputRows}/${reference.checksum}, " +
          s"candidate=${candidate.outputRows}/${candidate.checksum}"
    if mismatches.nonEmpty then
      throw new IllegalStateException(
        s"practical pipeline validation disagreed with the oracle: ${mismatches.mkString("; ")}"
      )

  private def requireComplete(
      results: Vector[RunResult],
      validations: Vector[Validation]
  ): Unit =
    val measured = results.iterator.map(shortBenchmark).toSet
    val expected = validations.iterator.map(_.benchmark).toSet
    val missing = (expected -- measured).toVector.sorted
    if missing.nonEmpty then
      throw new IllegalStateException(
        s"practical pipeline court omitted: ${missing.mkString(", ")}"
      )

  private def writeEnvironment(configuration: Configuration): Unit =
    val runtime = Runtime.getRuntime
    val properties = Vector(
      "receipt_format=1",
      "suite=frame4s-dplyr-practical-pipeline-court",
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
      "benchmark.heap=-Xms1g,-Xmx1g",
      "benchmark.jvm.flags=-XX:+AlwaysPreTouch",
      s"hardware=${sys.env.getOrElse("FRAME4S_HARDWARE", "unrecorded")}",
      "scala.version=3.7.4",
      "jmh.version=1.37",
      "frame4s.columnar.fallback=forbidden",
      "fixture.construction=outside-timing",
      "query.construction=outside-timing"
    )
    write(
      configuration.receipt.resolve("environment.properties"),
      properties.mkString("", "\n", "\n")
    )

  private def writeValidations(receipt: Path, validations: Vector[Validation]): Unit =
    val lines = validations.map: item =>
      Vector(
        item.benchmark,
        item.backend,
        item.outputRows.toString,
        item.checksum,
        item.structureChecksum,
        item.floatSignature,
        item.physicalPlan
      ).mkString("\t")
    write(
      receipt.resolve("validation.tsv"),
      (
        "benchmark\tbackend\toutput_rows\tchecksum\tstructure_checksum\tfloat_signature\tphysical_plan" +: lines
      ).mkString("", "\n", "\n")
    )

  private def writeSummary(
      configuration: Configuration,
      results: Vector[RunResult],
      validations: Vector[Validation]
  ): Unit =
    val byName = validations.map(value => value.benchmark -> value).toMap
    val rows = results
      .sortBy(result => (shortBenchmark(result), result.getParams.getMode.shortLabel()))
      .map: result =>
        val benchmark = shortBenchmark(result)
        val validation = byName(benchmark)
        val primary = result.getPrimaryResult
        val allocation = result.getSecondaryResults.asScala
          .get("gc.alloc.rate.norm")
          .map(value => f"${value.getScore}%.3f ${value.getScoreUnit}")
          .getOrElse("not reported")
        val score = f"${primary.getScore}%.6g ${primary.getScoreUnit}"
        s"| `$benchmark` | ${result.getParams.getMode.shortLabel()} | $score | $allocation | " +
          s"${validation.outputRows} | `${validation.checksum}` |"
    val status =
      if configuration.quick then "Quick wiring receipt; measurements are provisional."
      else "Full JMH practical-pipeline receipt."
    val tableRows = rows.mkString("\n|")
    val summary =
      s"""|# dplyr-style practical pipeline court
          |
          |$status Fixture construction, typed query construction, validation, and teardown occur
          |outside timed methods. The columnar rows forbid fallback and match the semantic reference
          |row count, raw-bit checksum, structural checksum, and floating signature before
          |measurement.
          |
          || Benchmark | Mode | Time or throughput | Allocation | Output rows | Checksum |
          ||---|---:|---:|---:|---:|---:|
          |$tableRows
          |
          |`filterWithColumnsSelect` combines two UTF-8 predicates, two immutable `withColumn`
          |derivations, and a final projection. `selectGroupSummarise` projects four columns, groups
          |by two nullable keys, and computes means over two measures.
          |""".stripMargin
    write(configuration.receipt.resolve("summary.md"), summary)

  private def shortBenchmark(result: RunResult): String =
    result.getParams.getBenchmark.split('.').takeRight(2).mkString(".")

  private def structureChecksum[S <: scala.NamedTuple.AnyNamedTuple](
      table: Table[S]
  ): Long =
    var hash = table.rowCount
    val batches = table.batches.iterator
    while batches.hasNext do
      val batch = batches.next()
      var row = 0
      while row < batch.rowCount do
        var column = 0
        while column < batch.columns.length do
          val scalar = batch
            .columns(column)
            .scalar(row)
            .fold(error => throw new IllegalStateException(error.message), identity)
          hash = hash * 31L + structureScalarHash(table.schema.fields(column).dataType, scalar)
          column += 1
        row += 1
    hash

  private def structureChecksum(result: ColumnarResult): Long =
    val rows =
      result.rows.fold(error => throw new IllegalStateException(error.message), identity)
    rows.foldLeft(result.rowCount): (hash, row) =>
      row
        .zip(result.schema.fields)
        .foldLeft(hash):
          case (current, (scalar, field)) =>
            current * 31L + structureScalarHash(field.dataType, scalar)

  private def structureScalarHash(dataType: DataType, value: ScalarValue): Long =
    (dataType, value) match
      case (DataType.Float32 | DataType.Float64, ScalarValue.Null) =>
        BenchmarkSupport.scalarHash(ScalarValue.Null)
      case (DataType.Float32 | DataType.Float64, _) => 0L
      case (_, other)                               => BenchmarkSupport.scalarHash(other)

  private def floatSignature[S <: scala.NamedTuple.AnyNamedTuple](
      table: Table[S]
  ): String =
    val evidence = floatEvidence(table.schema)
    val batches = table.batches.iterator
    var rowOffset = 0L
    while batches.hasNext do
      val batch = batches.next()
      var row = 0
      while row < batch.rowCount do
        var column = 0
        while column < batch.columns.length do
          val scalar = batch
            .columns(column)
            .scalar(row)
            .fold(error => throw new IllegalStateException(error.message), identity)
          addFloatEvidence(evidence, column, scalar, rowOffset + row.toLong)
          column += 1
        row += 1
      rowOffset += batch.rowCount.toLong
    renderFloatEvidence(evidence)

  private def floatSignature(result: ColumnarResult): String =
    val evidence = floatEvidence(result.schema)
    val rows =
      result.rows.fold(error => throw new IllegalStateException(error.message), identity)
    rows.zipWithIndex.foreach: (row, rowIndex) =>
      row.zipWithIndex.foreach: (scalar, column) =>
        addFloatEvidence(evidence, column, scalar, rowIndex.toLong)
    renderFloatEvidence(evidence)

  private def floatEvidence(schema: Schema): Array[Option[FloatEvidence]] =
    schema.fields
      .map: field =>
        field.dataType match
          case DataType.Float32 | DataType.Float64 =>
            Some(
              FloatEvidence(
                count = 0L,
                sum = 0.0,
                sumSquares = 0.0,
                minimum = Double.PositiveInfinity,
                maximum = Double.NegativeInfinity,
                rowWeightedSum = 0.0,
                rowWeightedSumSquares = 0.0
              )
            )
          case _ => None
      .toArray

  private def addFloatEvidence(
      evidence: Array[Option[FloatEvidence]],
      column: Int,
      value: ScalarValue,
      row: Long
  ): Unit =
    evidence(column) match
      case Some(current) =>
        value match
          case ScalarValue.Float32(actual) =>
            evidence(column) = Some(current.add(actual.toDouble, row))
          case ScalarValue.Float64(actual) =>
            evidence(column) = Some(current.add(actual, row))
          case ScalarValue.Null => ()
          case other            =>
            throw new IllegalStateException(
              s"floating column $column produced ${other.getClass.getSimpleName}"
            )
      case None => ()

  private def renderFloatEvidence(evidence: Array[Option[FloatEvidence]]): String =
    evidence.iterator.zipWithIndex
      .collect:
        case (Some(value), index) => value.render(index)
      .mkString(";")

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
