package frame4s.fs2.examples

import cats.effect.{IO, IOApp, Resource}
import fs2.io.file.Path
import frame4s.*
import frame4s.fs2.*

/** Receipt-bearing CSV example: typed path to pure query to bounded output in one visible
  * `Resource` scope.
  *
  * The specimen intentionally uses no handwritten runtime `Schema`, manual batch,
  * `ReferenceSources`, partial `.get`, internal member, or unsafe cast.
  */
object CsvFirstUse extends IOApp.Simple:
  type Input = (id: Int, label: String, score: Option[Double])

  private val csv =
    """id,label,score
      |1,alpha,1.5
      |2,"quoted,label",
      |3,東京,NaN
      |""".stripMargin

  private def frame[A](result: Either[FrameError, A]): IO[A] =
    IO.fromEither(result.left.map(error => new IllegalArgumentException(error.message)))

  private def temporaryCsv: Resource[IO, Path] =
    Resource.make(
      IO.blocking:
        val nio = java.nio.file.Files.createTempFile("frame4s-first-use-", ".csv")
        java.nio.file.Files.writeString(nio, csv)
        Path.fromNioPath(nio)
    )((path: Path) => IO.blocking(java.nio.file.Files.deleteIfExists(path.toNioPath)).void)

  private def output: Resource[IO, (String, ExecutionReceipt)] =
    for
      path <- temporaryCsv
      reference <- Resource.eval(frame(SourceRef.scan("first-use-csv", "first-use CSV")))
      binding = CsvPathSource.binding[IO, Input](
        reference,
        path,
        CsvSettings(batchSize = 2)
      )
      query = binding.frame
        .filter(row => row.col("id") > 1)
        .select: row =>
          (
            row.col("label"),
            row.col("score")
          )
      runtime <- FrameRuntime.resource(binding)
      result <- runtime.collectWithReceipt(query)
      rendered <- Resource.eval(
        IO.fromEither(
          result.table
            .show(TableRenderOptions(maxRows = 10, maxWidth = 72))
            .left
            .map(TableReadFailure.apply)
        )
      )
    yield (rendered, result.receipt)

  def run: IO[Unit] =
    output.use: (rendered, receipt) =>
      IO.println(
        s"pushdown accepted: ${receipt.accepted.map(_._2).mkString(", ")}"
      ) *> IO.println(rendered)
