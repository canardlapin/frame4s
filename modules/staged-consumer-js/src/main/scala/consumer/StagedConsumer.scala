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

    FrameRuntime
      .resource(binding)
      .flatMap(_.collectWithReceipt(query))
      .use: execution =>
        for
          labels <- IO.fromEither(
            execution.table.column("label").left.map(TableReadFailure.apply)
          )
          engine <- IO.fromOption(execution.receipt.engine)(
            new IllegalStateException("staged Scala.js collect produced no engine receipt")
          )
          _ <- IO.raiseWhen(
            engine.engine != EngineId.Columnar || engine.fallback.nonEmpty
          )(
            new IllegalStateException(s"unexpected staged Scala.js engine receipt: $engine")
          )
          _ <- IO.raiseWhen(
            execution.receipt.sources.map(_.pushdown.columnsRead) !=
              Vector(Vector("id", "label", "score"))
          )(
            new IllegalStateException(
              s"unexpected staged Scala.js source receipt: ${execution.receipt.sources}"
            )
          )
          _ <- IO.raiseWhen(labels != Vector("alpha", "東京"))(
            new IllegalStateException(s"unexpected staged Scala.js rows: $labels")
          )
          _ <- IO.println("staged-js=ok")
        yield ()
