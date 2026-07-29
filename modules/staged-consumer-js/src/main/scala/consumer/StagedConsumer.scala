package consumer

import cats.effect.{IO, IOApp}
import frame4s.*
import frame4s.fs2.*

object StagedConsumer extends IOApp.Simple:
  type Input = (id: Int, label: String, score: Option[Double])
  type Output = (label: String, root: Option[Double])

  def run: IO[Unit] =
    val binding = InMemoryFrameSource.rows[IO, Input](
      Vector(
        (id = 1, label = "alpha", score = Some(9.0)),
        (id = 2, label = "東京", score = None)
      ),
      batchSize = 1
    )
    val query: Frame[Output] = binding.frame
      .filter(row => row.col("id") > 0)
      .select: row =>
        (
          row.col("label"),
          row.col("score").sqrt.as("root")
        )

    for
      labels <- binding
        .collect(query)
        .use(table =>
          IO.fromEither(
            table.column("label").left.map(TableReadFailure.apply)
          )
        )
      _ <- IO.raiseWhen(labels != Vector("alpha", "東京"))(
        new IllegalStateException(s"unexpected staged Scala.js rows: $labels")
      )
      _ <- IO.println("staged-js=ok")
    yield ()
