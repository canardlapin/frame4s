package frame4s.benchmarks

import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import frame4s.*
import frame4s.fs2.FrameRuntime

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

  private val Warmup = 3
  private val Iterations = 7

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

  private def publicCollect[S <: scala.NamedTuple.AnyNamedTuple](
      runtime: FrameRuntime[IO],
      query: Frame[S],
      expectedBackend: String,
      expectedPlan: String
  ): Long =
    runtime
      .collectWithReceipt(query)
      .use: execution =>
        IO:
          val engine = execution.receipt.engine.getOrElse(
            sys.error("public collect produced no engine receipt")
          )
          if engine.backend != expectedBackend then
            sys.error(
              s"expected backend $expectedBackend, measured ${engine.backend} " +
                s"(fallback=${engine.fallback})"
            )
          if !engine.physicalPlan.contains(expectedPlan) then
            sys.error(s"expected plan containing $expectedPlan, measured ${engine.physicalPlan}")
          execution.table.rowCount
      .unsafeRunSync()

  private def engineCollect(plan: LogicalPlan, sources: ReferenceSources): Long =
    ColumnarInterpreter.collect(plan, sources) match
      case Left(reason)     => sys.error(s"engine declined: $reason")
      case Right(collected) =>
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
      Table[Left](table(summon[SchemaDescriptor[Left]].schema, shuffled(rows, 41L), 3L))
    )
    val rightTable = storage(
      Table[Right](table(summon[SchemaDescriptor[Right]].schema, shuffled(rows, 97L), 5L))
    )
    val sources =
      ReferenceSources.empty.bind(leftRef, leftTable).bind(rightRef, rightTable)
    val runtime = FrameRuntime[IO](sources)

    val left = frame(Frame.values[Left](leftRef))
    val right = frame(Frame.values[Right](rightRef))
    val joined: Frame[Joined] =
      left.innerJoin(right)((lhs, rhs) => lhs.col("key") === rhs.col("rightKey"))
    val filtered: Frame[Left] = left.filter(row => row.col("key") > rows / 2)

    val results = Vector(
      measure("filter", "engine-collect")(() => engineCollect(filtered.plan, sources)),
      measure("filter", "public-collect")(() =>
        publicCollect(runtime, filtered, "columnar", "Filter")
      ),
      measure("one-to-one-join-shuffled", "engine-collect")(() =>
        engineCollect(joined.plan, sources)
      ),
      measure("one-to-one-join-shuffled", "public-collect")(() =>
        publicCollect(runtime, joined, "columnar", "HashJoin")
      ),
      measure("bare-scan-fallback", "public-collect")(() =>
        publicCollect(runtime, left, "reference", "ReferenceWholePlan")
      )
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
