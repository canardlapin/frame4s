package consumer

import cats.effect.{IO, IOApp}
import frame4s.*
import frame4s.fs2.*

object StagedConsumer extends IOApp.Simple:
  type Input = (id: Int, label: String, score: Option[Double])

  def run: IO[Unit] =
    val binding = InMemoryFrameSource.rows[IO, Input](
      Vector(
        (id = 1, label = "alpha", score = Some(4.0)),
        (id = 2, label = "東京", score = None)
      ),
      batchSize = 1
    )
    val query = binding.frame
      .filter(row => row.col("id") > 0)
      .select: row =>
        (
          row.col("label"),
          row.col("score").sqrt.as("root")
        )

    for
      rendered <- binding.render(query, TableRenderOptions(maxRows = 4, maxWidth = 60))
      _ <- IO.raiseWhen(!rendered.contains("東京"))(
        new IllegalStateException("staged JVM consumer lost its typed UTF-8 row")
      )
      _ <- IO.println("staged-jvm=ok")
    yield ()
