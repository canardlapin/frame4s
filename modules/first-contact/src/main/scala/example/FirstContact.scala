package example

import cats.effect.{IO, IOApp, Resource}
import fs2.io.file.Path
import frame4s.*
import frame4s.fs2.*

/** A downstream-package release specimen, deliberately restricted to frame4s's public API. */
object FirstContact extends IOApp.Simple:
  type Observation = (id: Int, label: String, score: Option[Double])
  type Selected = (label: String, score: Option[Double])

  private val csv =
    """id,label,score
      |1,alpha,1.5
      |2,"quoted,label",
      |3,東京,NaN
      |""".stripMargin

  private def frame[A](value: Either[FrameError, A]): IO[A] =
    IO.fromEither(value.left.map(error => new IllegalArgumentException(error.message)))

  private def inputPath: Resource[IO, Path] =
    Resource.make(
      IO.blocking:
        val nio = java.nio.file.Files.createTempFile("frame4s-consumer-", ".csv")
        java.nio.file.Files.writeString(nio, csv)
        Path.fromNioPath(nio)
    )((path: Path) => IO.blocking(java.nio.file.Files.deleteIfExists(path.toNioPath)).void)

  private def program: Resource[IO, String] =
    for
      path <- inputPath
      reference <- Resource.eval(frame(SourceRef.scan("observations", "observations")))
      source = CsvPathSource.binding[IO, Observation](
        reference,
        path,
        CsvSettings(batchSize = 2)
      )
      query: Frame[Selected] = source.frame
        .filter(row => row.col("id") > Expr.literal(1))
        .select: row =>
          (
            row.col("label").as("label"),
            row.col("score").as("score")
          )
      runtime <- FrameRuntime.resource(source)
      result <- runtime.collectWithReceipt(query)
      labels <- Resource.eval(
        IO.fromEither(
          result.table.column("label").left.map(TableReadFailure.apply)
        )
      )
      _ <- Resource.eval(
        IO.raiseWhen(labels != Vector("quoted,label", "東京"))(
          new IllegalStateException(s"unexpected typed labels: $labels")
        )
      )
      rendered <- Resource.eval(
        IO.fromEither(
          result.table
            .show(TableRenderOptions(maxRows = 8, maxWidth = 72))
            .left
            .map(TableReadFailure.apply)
        )
      )
    yield rendered

  def run: IO[Unit] =
    program.use(IO.println)
