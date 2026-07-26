package consumer

import cats.effect.{IO, IOApp}
import frame4s.*
import frame4s.fs2.*

object StagedConsumer extends IOApp.Simple:
  type Input = (id: Int, label: String, score: Option[Double])
  type Output = (label: String, root: Option[Double])

  private def checked[A](result: Either[FrameError, A]): IO[A] =
    IO.fromEither(result.left.map(error => new IllegalArgumentException(error.message)))

  def run: IO[Unit] =
    for
      reference <- checked(SourceRef.values("staged-jvm", "staged-jvm"))
      binding = InMemoryFrameSource.rowsBinding[IO, Input](
        reference,
        Vector(
          (id = 1, label = "alpha", score = Some(4.0)),
          (id = 2, label = "東京", score = None)
        ),
        batchSize = 1
      )
      query: Frame[Output] = binding.frame
        .filter(row => row.col("id") > Expr.literal(0))
        .select: row =>
          (
            row.col("label").as("label"),
            row.col("score").sqrt.as("root")
          )
      rendered <- FrameRuntime
        .resource(binding)
        .flatMap(_.collect(query))
        .use(table =>
          IO.fromEither(
            table
              .show(TableRenderOptions(maxRows = 4, maxWidth = 60))
              .left
              .map(TableReadFailure.apply)
          )
        )
      _ <- IO.raiseWhen(!rendered.contains("東京"))(
        new IllegalStateException("staged JVM consumer lost its typed UTF-8 row")
      )
      _ <- IO.println("staged-jvm=ok")
    yield ()
