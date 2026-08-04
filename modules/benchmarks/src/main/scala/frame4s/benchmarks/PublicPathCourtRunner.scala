package frame4s.benchmarks

import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import frame4s.*
import frame4s.fs2.{EngineId, FrameRuntime}

/** Measures the public materializing collect against the internal engine path.
  *
  * Wall-clock, process-level timing with warmup, the same methodology as the pandas and Polars
  * courts. Every public iteration asserts the engine receipt: the supported workloads must run on
  * the columnar backend with no fallback, and the bare-scan workload must run on the reference
  * backend with its reason surfaced, so a silent-decline regression fails the court rather than
  * quietly measuring the reference interpreter twice.
  */
object PublicPathCourtRunner:
  import BenchmarkSupport.*

  type Left = (key: Int, leftValue: Long)
  type Right = (rightKey: Int, rightValue: Long)
  type Joined = (key: Int, leftValue: Long, rightKey: Int, rightValue: Long)
  type Grouped = (key: Int, n: Long)

  private val Warmup = 5
  private val Iterations = 15

  final private case class Measured(
      workload: String,
      endpoint: String,
      medianMs: Double,
      minMs: Double,
      maxMs: Double,
      outputRows: Long
  )

  private def shuffled(rows: Int, seed: Long): Array[Int] =
    val keys = Array.tabulate(rows)(identity)
    var state = seed
    var index = rows - 1
    while index > 0 do
      state = state * 6364136223846793005L + 1442695040888963407L
      val pick = ((state >>> 33) % (index + 1)).toInt
      val kept = keys(index)
      keys(index) = keys(pick)
      keys(pick) = kept
      index -= 1
    keys

  private def table[S <: scala.NamedTuple.AnyNamedTuple](
      schema: Schema,
      keys: Array[Int],
      multiplier: Long
  ): Vector[RecordBatch] =
    val payload = Array.tabulate(keys.length)(row => row.toLong * multiplier)
    Vector(
      storage(
        RecordBatch(
          schema,
          Vector(
            storage(ColumnArray.int32(keys)),
            storage(ColumnArray.int64(payload))
          )
        )
      )
    )

  private def timed(work: () => Long): (Vector[Double], Long) =
    var index = 0
    while index < Warmup do
      val _ = work()
      index += 1
    var rows = 0L
    val samples = Vector.newBuilder[Double]
    index = 0
    while index < Iterations do
      val start = System.nanoTime()
      rows = work()
      samples += (System.nanoTime() - start) / 1e6
      index += 1
    (samples.result(), rows)

  private def measure(workload: String, endpoint: String)(work: () => Long): Measured =
    val (samples, rows) = timed(work)
    val sorted = samples.sorted
    Measured(
      workload,
      endpoint,
      medianMs = sorted(sorted.length / 2),
      minMs = sorted.head,
      maxMs = sorted.last,
      outputRows = rows
    )

  /** Measure two endpoints as alternating pairs so neither endpoint always receives the later,
    * warmer part of the process lifetime.
    */
  private def measurePair(
      workload: String,
      firstEndpoint: String,
      secondEndpoint: String
  )(
      first: () => Long,
      second: () => Long
  ): Vector[Measured] =
    def sample(work: () => Long): (Double, Long) =
      val start = System.nanoTime()
      val rows = work()
      ((System.nanoTime() - start) / 1e6, rows)

    var index = 0
    while index < Warmup do
      if (index & 1) == 0 then
        val _ = first()
        val _ = second()
      else
        val _ = second()
        val _ = first()
      index += 1

    val firstSamples = Vector.newBuilder[Double]
    val secondSamples = Vector.newBuilder[Double]
    var firstRows = 0L
    var secondRows = 0L
    index = 0
    while index < Iterations do
      if (index & 1) == 0 then
        val firstResult = sample(first)
        val secondResult = sample(second)
        firstSamples += firstResult(0)
        secondSamples += secondResult(0)
        firstRows = firstResult(1)
        secondRows = secondResult(1)
      else
        val secondResult = sample(second)
        val firstResult = sample(first)
        secondSamples += secondResult(0)
        firstSamples += firstResult(0)
        secondRows = secondResult(1)
        firstRows = firstResult(1)
      index += 1

    def result(endpoint: String, samples: Vector[Double], rows: Long): Measured =
      val sorted = samples.sorted
      Measured(
        workload,
        endpoint,
        medianMs = sorted(sorted.length / 2),
        minMs = sorted.head,
        maxMs = sorted.last,
        outputRows = rows
      )

    Vector(
      result(firstEndpoint, firstSamples.result(), firstRows),
      result(secondEndpoint, secondSamples.result(), secondRows)
    )

  private def publicCollect[S <: scala.NamedTuple.AnyNamedTuple](
      runtime: FrameRuntime[IO],
      query: Frame[S],
      expectedEngine: EngineId,
      expectedPlan: String
  ): Long =
    runtime
      .collectWithReceipt(query)
      .use: execution =>
        IO:
          val engine = execution.receipt.engine.getOrElse(
            sys.error("public collect produced no engine receipt")
          )
          if engine.engine != expectedEngine then
            sys.error(
              s"expected engine $expectedEngine, measured ${engine.engine} " +
                s"(fallback=${engine.fallback})"
            )
          if !engine.physicalPlan.contains(expectedPlan) then
            sys.error(s"expected plan containing $expectedPlan, measured ${engine.physicalPlan}")
          execution.table.rowCount
      .unsafeRunSync()

  private def engineCollect(
      plan: LogicalPlan,
      sources: ReferenceSources,
      expectedPlan: String
  ): Long =
    ColumnarInterpreter.collect(plan, sources) match
      case Left(reason)     => sys.error(s"engine declined: $reason")
      case Right(collected) =>
        if collected.receipt.fallback.nonEmpty then
          sys.error(
            s"expected direct engine execution, measured fallback ${collected.receipt.fallback}"
          )
        if !collected.receipt.physicalPlan.contains(expectedPlan) then
          sys.error(
            s"expected engine plan containing $expectedPlan, measured " +
              collected.receipt.physicalPlan
          )
        val rows = collected.batches.map(_.rowCount.toLong).sum
        collected.batches.foreach(_.close())
        rows

  def main(args: Array[String]): Unit =
    val receipt = args.sliding(2, 2).collectFirst { case Array("--receipt", value) =>
      Paths.get(value)
    }
    val rows = args
      .sliding(2, 2)
      .collectFirst { case Array("--rows", value) => value.toInt }
      .getOrElse(1_000_000)
    val target = receipt.getOrElse(sys.error("usage: --receipt <dir> [--rows N]"))
    Files.createDirectories(target)

    val leftRef = frame(SourceRef.values("public-left", "public-left"))
    val rightRef = frame(SourceRef.values("public-right", "public-right"))
    val leftTable = storage(
      Table.takeOwnership[Left](
        table(summon[SchemaDescriptor[Left]].schema, shuffled(rows, 41L), 3L)
      )
    )
    val rightTable = storage(
      Table.takeOwnership[Right](
        table(summon[SchemaDescriptor[Right]].schema, shuffled(rows, 97L), 5L)
      )
    )
    val sources =
      ReferenceSources.empty.bind(leftRef, leftTable).bind(rightRef, rightTable)
    val runtime = FrameRuntime[IO](sources)

    val left = frame(Frame.values[Left](leftRef))
    val right = frame(Frame.values[Right](rightRef))
    val joined: Frame[Joined] =
      left.innerJoin(right)((lhs, rhs) => lhs.col("key") === rhs.col("rightKey"))
    val filtered: Frame[Left] = left.filter(row => row.col("key") > rows / 2)
    val grouped: Frame[Grouped] =
      left
        .groupBy(row => Tuple1(row.col("key").as("key")))
        .aggregate(_ => Tuple1(Aggregate.count.as("n")))

    val filterResults = measurePair("filter", "engine-collect", "public-collect")(
      () => engineCollect(filtered.plan, sources, "Filter"),
      () => publicCollect(runtime, filtered, EngineId.Columnar, "Filter")
    )
    val joinResults = measurePair(
      "one-to-one-join-shuffled",
      "engine-collect",
      "public-collect"
    )(
      () => engineCollect(joined.plan, sources, "HashJoin"),
      () => publicCollect(runtime, joined, EngineId.Columnar, "HashJoin")
    )
    val groupResults = measurePair(
      "high-cardinality-group-shuffled",
      "engine-collect",
      "public-collect"
    )(
      () => engineCollect(grouped.plan, sources, "HashAggregate"),
      () => publicCollect(runtime, grouped, EngineId.Columnar, "HashAggregate")
    )
    val results = filterResults ++ joinResults ++ groupResults :+
      measure("bare-scan-fallback", "public-collect")(() =>
        publicCollect(runtime, left, EngineId.Reference, "ReferenceWholePlan")
      )

    val metrics = new StringBuilder
    metrics.append("workload\tendpoint\tmedian_ms\tmin_ms\tmax_ms\toutput_rows\n")
    results.foreach: result =>
      metrics.append(
        f"${result.workload}\t${result.endpoint}\t${result.medianMs}%.6f\t" +
          f"${result.minMs}%.6f\t${result.maxMs}%.6f\t${result.outputRows}%d%n"
      )
    Files.write(target.resolve("metrics.tsv"), metrics.toString.getBytes(StandardCharsets.UTF_8))

    val environment = new StringBuilder
    environment.append(s"rows=$rows\n")
    environment.append(s"warmup=$Warmup\n")
    environment.append(s"iterations=$Iterations\n")
    environment.append(s"jvm=${System.getProperty("java.version")}\n")
    environment.append(s"hardware=${sys.env.getOrElse("FRAME4S_HARDWARE", "unknown")}\n")
    Files.write(
      target.resolve("environment.properties"),
      environment.toString.getBytes(StandardCharsets.UTF_8)
    )

    leftTable.close()
    rightTable.close()
    println(s"public-path court written to $target")
