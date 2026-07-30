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
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*

/** Measures join preparation, gathered-view construction, deep materialization, and matched
  * consumption as distinct endpoints.
  */
object JoinMatchedEndpointCourtRunner:
  final private case class Configuration(
      receipt: Path,
      sizes: Vector[Int],
      orders: Vector[String],
      heap: String,
      processRound: Int,
      sequencePosition: Int,
      quick: Boolean
  )

  final private case class Metric(
      rows: Int,
      order: String,
      endpoint: String,
      milliseconds: Double,
      allocation: Double
  )

  final private case class Validation(
      rows: Int,
      order: String,
      outputRows: Long,
      schema: String,
      orderedOutputSha256: String,
      checksum: String,
      columnSum: String,
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

  private val Endpoints = Map(
    "joinPrepare" -> "prepare",
    "joinExecutionOnly" -> "gather-view",
    "joinDeepMaterialized" -> "deep-materialized",
    "joinMatchedConsumption" -> "matched-consumption"
  )
  private val OutputColumns = Vector("key", "leftValue", "rightKey", "rightValue")

  def main(arguments: Array[String]): Unit =
    val configuration = parse(arguments.toList)
    Files.createDirectories(configuration.receipt.resolve("raw"))
    val raw = configuration.receipt.resolve("raw/jmh.json")
    val log = configuration.receipt.resolve("raw/jmh.log")
    val builder = new OptionsBuilder()
      .include(
        "frame4s\\.benchmarks\\.JoinRegimeBenchmarks\\." +
          "join(Prepare|ExecutionOnly|DeepMaterialized|MatchedConsumption)"
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
    writeSummary(configuration, metrics)

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

    val sizes = commaSeparated("--sizes", "1000000,4000000").map(_.toInt)
    if sizes.isEmpty || sizes.exists(_ <= 0) then
      throw new IllegalArgumentException("--sizes must contain positive integers")
    val orders = commaSeparated("--orders", JoinRegimeFixture.Orders.mkString(","))
    val invalidOrders = orders.filterNot(JoinRegimeFixture.Orders.contains)
    if invalidOrders.nonEmpty then
      throw new IllegalArgumentException(
        s"unsupported --orders: ${invalidOrders.mkString(", ")}"
      )
    val processRound = value("--round").fold(1)(_.toInt)
    val sequencePosition = value("--sequence-position").fold(1)(_.toInt)
    if processRound <= 0 || sequencePosition <= 0 then
      throw new IllegalArgumentException("round and sequence position must be positive")

    Configuration(
      receipt = Path.of:
        value("--receipt").getOrElse:
          throw new IllegalArgumentException("--receipt <directory> is required")
      ,
      sizes = sizes.distinct,
      orders = orders.distinct,
      heap = value("--heap").getOrElse("4g"),
      processRound = processRound,
      sequencePosition = sequencePosition,
      quick = arguments.contains("--quick")
    )

  private def profileConfigurations(
      configuration: Configuration
  ): (Vector[Validation], Vector[StageSample]) =
    val validations = Vector.newBuilder[Validation]
    val stages = Vector.newBuilder[StageSample]
    val samples = if configuration.quick then 1 else 3

    configuration.sizes.foreach: rows =>
      configuration.orders.foreach: order =>
        val state = new JoinRegimeState
        state.rows = rows
        state.order = order
        state.setup()
        try
          var sample = 1
          while sample <= samples do
            val profiled = state.execution.profileRun()
            if profiled.run.receipt.fallback.nonEmpty then
              throw new IllegalStateException(s"$rows/$order unexpectedly fell back")
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

              val materializeStarted = System.nanoTime()
              val batches = result.recordBatches.fold(
                reason => throw new IllegalStateException(reason),
                identity
              )
              stages += StageSample(
                rows,
                order,
                sample,
                "deep-materialize",
                (System.nanoTime() - materializeStarted).toDouble / 1e6
              )
              try
                val consumeStarted = System.nanoTime()
                val checksum = checksumBatches(batches)
                val columnSum = sumBatches(batches)
                stages += StageSample(
                  rows,
                  order,
                  sample,
                  "matched-consume",
                  (System.nanoTime() - consumeStarted).toDouble / 1e6
                )
                val outputRows = batches.foldLeft(0L)(_ + _.rowCount.toLong)
                if outputRows != rows.toLong then
                  throw new IllegalStateException(
                    s"$rows/$order materialized $outputRows rows"
                  )
                if checksum != state.expectedChecksum then
                  throw new IllegalStateException(
                    s"$rows/$order deep checksum ${java.lang.Long.toUnsignedString(checksum)} " +
                      s"!= ${java.lang.Long.toUnsignedString(state.expectedChecksum)}"
                  )
                if columnSum != state.expectedColumnSum then
                  throw new IllegalStateException(
                    s"$rows/$order column sum $columnSum != ${state.expectedColumnSum}"
                  )
                if sample == 1 then
                  validations += Validation(
                    rows,
                    order,
                    outputRows,
                    schemaLabel(result.schema),
                    orderedOutputDigest(batches),
                    java.lang.Long.toUnsignedString(checksum),
                    java.lang.Long.toUnsignedString(columnSum),
                    state.leftDigest,
                    state.rightDigest
                  )
              finally batches.foreach(_.close())
            finally result.close()
            sample += 1
        finally state.tearDown()

    (validations.result(), stages.result())

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

  private def sumBatches(batches: Vector[RecordBatch]): Long =
    var total = 0L
    var batchIndex = 0
    while batchIndex < batches.length do
      val columns = batches(batchIndex).columns
      var column = 0
      while column < columns.length do
        columns(column) match
          case values: Int32Array =>
            var row = 0
            while row < values.length do
              total += values
                .value(row)
                .fold(error => throw new IllegalStateException(error.message), _.toLong)
              row += 1
          case values: Int64Array =>
            var row = 0
            while row < values.length do
              total += values
                .value(row)
                .fold(error => throw new IllegalStateException(error.message), identity)
              row += 1
          case values =>
            throw new IllegalStateException(
              s"matched join validation does not support ${values.dataType}"
            )
        column += 1
      batchIndex += 1
    total

  private def orderedOutputDigest(batches: Vector[RecordBatch]): String =
    val digest = MessageDigest.getInstance("SHA-256")
    OutputColumns.foreach: name =>
      batches.foreach: batch =>
        val column = batch
          .column(name)
          .fold(
            error => throw new IllegalStateException(error.message),
            identity
          )
        if column.nullCount != 0 then
          throw new IllegalStateException(s"matched output $name contains nulls")
        val buffers = column.copyPhysicalBuffers.fold(
          error => throw new IllegalStateException(error.message),
          identity
        )
        digest.update(buffers.last)
    digest.digest().map(byte => f"${byte & 0xff}%02x").mkString

  private def schemaLabel(schema: Schema): String =
    schema.fields
      .map(field =>
        s"${field.name}:${field.dataType}:${if field.nullable then "nullable" else "required"}"
      )
      .mkString(",")

  private def extractMetrics(results: Vector[RunResult]): Vector[Metric] =
    results.map: result =>
      val method = result.getParams.getBenchmark.split('.').last
      val endpoint = Endpoints.getOrElse(
        method,
        throw new IllegalStateException(s"unexpected matched endpoint $method")
      )
      val allocation = result.getSecondaryResults.asScala
        .get("gc.alloc.rate.norm")
        .map(_.getScore)
        .getOrElse:
          throw new IllegalStateException(s"$method has no normalized allocation")
      Metric(
        result.getParams.getParam("rows").toInt,
        result.getParams.getParam("order"),
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
          configuration.orders.flatMap: order =>
            Endpoints.values.map(endpoint => (rows, order, endpoint))
        .toSet
    val actual = metrics.map(metric => (metric.rows, metric.order, metric.endpoint)).toSet
    val missing = expected -- actual
    if missing.nonEmpty then
      throw new IllegalStateException(
        s"matched endpoint court omitted ${missing.toVector.sorted.mkString(", ")}"
      )

  private def writeEnvironment(configuration: Configuration): Unit =
    val runtime = Runtime.getRuntime
    val properties = Vector(
      "receipt_format=1",
      "suite=frame4s-join-matched-endpoint-court",
      s"quick=${configuration.quick}",
      s"process_round=${configuration.processRound}",
      s"sequence_position=${configuration.sequencePosition}",
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
      "schema=key:Int32,leftValue:Int64,rightKey:Int32,rightValue:Int64",
      "order_contract=stable-left",
      "prepare=reported-separately",
      "gather-view=internal-frame4s-diagnostic-only",
      "deep-materialized=owned-physical-column-buffers",
      "matched-consumption=deep-materialization-plus-four-column-sum",
      "input_creation=outside-timing"
    )
    write(
      configuration.receipt.resolve("environment.properties"),
      properties.mkString("", "\n", "\n")
    )

  private def writeMetrics(receipt: Path, metrics: Vector[Metric]): Unit =
    val rows = metrics
      .sortBy(metric => (metric.rows, metric.order, metric.endpoint))
      .map: metric =>
        f"${metric.rows}\t${metric.order}\t${metric.endpoint}\t" +
          f"${metric.milliseconds}%.9f\t${metric.allocation}%.3f"
    write(
      receipt.resolve("metrics.tsv"),
      (
        "rows\torder\tendpoint\tmilliseconds\tallocation_bytes" +: rows
      ).mkString("", "\n", "\n")
    )

  private def writeValidations(
      receipt: Path,
      validations: Vector[Validation]
  ): Unit =
    val rows = validations
      .sortBy(value => (value.rows, value.order))
      .map: value =>
        s"${value.rows}\t${value.order}\t${value.outputRows}\t${value.schema}\t" +
          s"${value.orderedOutputSha256}\t${value.checksum}\t${value.columnSum}\t" +
          s"${value.leftDigest}\t${value.rightDigest}"
    write(
      receipt.resolve("validation.tsv"),
      (
        (
          "rows\torder\toutput_rows\tschema\tordered_output_sha256\tchecksum\t" +
            "column_sum\tleft_keys_sha256\tright_keys_sha256"
        ) +: rows
      ).mkString("", "\n", "\n")
    )

  private def writeStages(receipt: Path, samples: Vector[StageSample]): Unit =
    val rows = samples.map: value =>
      f"${value.rows}\t${value.order}\t${value.sample}\t${value.stage}\t" +
        f"${value.milliseconds}%.6f"
    write(
      receipt.resolve("raw/stages.tsv"),
      (
        "rows\torder\tsample\tstage\tmilliseconds" +: rows
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

  private def writeSummary(
      configuration: Configuration,
      metrics: Vector[Metric]
  ): Unit =
    val rows = metrics
      .sortBy(metric => (metric.rows, configuration.orders.indexOf(metric.order), metric.endpoint))
      .map: metric =>
        f"| ${metric.rows} | ${metric.order} | ${metric.endpoint} | " +
          f"${metric.milliseconds}%.3f | ${metric.allocation / 1000000.0}%.2f |"
    val mode =
      if configuration.quick then "Quick provisional receipt." else "Full receipt."
    val summary =
      s"""@# frame4s matched join endpoint court
          @
          @$mode Process round ${configuration.processRound}, sequence position
          @${configuration.sequencePosition}. `gather-view` is an internal diagnostic and is not a
          @dataframe comparator numerator. `deep-materialized` owns physical output buffers;
          @`matched-consumption` additionally sums all four output columns.
          @
          @| Rows | Key order | Endpoint | ms/op | Allocation MB/op |
          @|---:|---|---|---:|---:|
          @${rows.mkString("\n")}
          @
          @Exact stable-left output digests, schema, cardinality, checksums, and fixture
          @fingerprints are in `validation.tsv`. Stage attribution is in `stage-summary.tsv`.
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
