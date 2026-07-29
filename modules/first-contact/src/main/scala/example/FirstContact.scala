package example

import cats.effect.{IO, IOApp}
import frame4s.*
import frame4s.fs2.*

/** Downstream-package first contact, restricted to frame4s's public API. */
object FirstContact extends IOApp.Simple:
  type People = (id: Int, name: String, score: Option[Double])

  private val source = InMemoryFrameSource.rows[IO, People](
    Vector(
      (id = 1, name = "Ada", score = Some(9.5)),
      (id = 2, name = "Lin", score = None)
    )
  )

  private val query = source.frame
    .filter(row => row.col("id") > 0)
    .select(row => (row.col("name"), row.col("score")))

  def run: IO[Unit] =
    source
      .render(query, TableRenderOptions(maxRows = 5, maxWidth = 60))
      .flatMap: rendered =>
        IO.raiseWhen(!rendered.contains("Ada") || !rendered.contains("null"))(
          new IllegalStateException("first-contact rendering lost a typed row")
        ) *> IO.println(rendered)
