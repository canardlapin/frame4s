package consumer

import cats.effect.IO
import cats.effect.IOApp
import frame4s.*
import frame4s.fs2.*

object StagedArrowConsumer extends IOApp.Simple:
  type Input = (id: Int, label: String, at: TimestampMicros)

  private val schema = summon[SchemaDescriptor[Input]].schema

  def run: IO[Unit] =
    val input = InMemoryFrameSource.rows[IO, Input](
      Vector(
        (id = 1, label = "arrow", at = TimestampMicros(1000L)),
        (id = 2, label = "東京", at = TimestampMicros(2000L))
      ),
      batchSize = 1
    )

    new ArrowIpcFrameSink[IO]()
      .write(schema, input.stream(input.frame))
      .flatMap:
        case Left(error)    => IO.raiseError(new IllegalStateException(error.message))
        case Right(written) =>
          ArrowIpcFrameSource
            .resource[IO](written.bytes)
            .use(_.inspect)
            .flatMap:
              case Right(inspection) if inspection.schema == schema =>
                IO.println("staged-arrow=ok")
              case Right(inspection) =>
                IO.raiseError(
                  new IllegalStateException(
                    s"Arrow schema changed: expected $schema, found ${inspection.schema}"
                  )
                )
              case Left(error) => IO.raiseError(new IllegalStateException(error.message))
