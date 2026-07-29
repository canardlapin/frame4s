package frame4s.fs2.examples

import cats.effect.{IO, IOApp, Resource}
import frame4s.*
import frame4s.fs2.*

/** A multi-source relational workflow using only the public acquisition and materialization API. */
object FrameWorkflow extends IOApp.Simple:
  type People = (id: Int, team: String, score: Option[Double])
  type Teams = (team: String, region: String)
  type Result = (region: String, n: Long, meanScore: Option[Double])
  type LeftResult = (id: Int, region: Option[String])

  private val peopleRows = Vector(
    (id = 1, team = "a", score = Some(2.0)),
    (id = 2, team = "b", score = None),
    (id = 3, team = "a", score = Some(4.0))
  )
  private val teamRows = Vector(
    (team = "a", region = "north"),
    (team = "b", region = "south")
  )

  private def checked[A](result: Either[FrameError, A]): IO[A] =
    IO.fromEither(result.left.map(error => new IllegalArgumentException(error.message)))

  private def rendered[S <: scala.NamedTuple.AnyNamedTuple](
      runtime: FrameRuntime[IO],
      query: Frame[S]
  ): IO[String] =
    runtime
      .collect(query)
      .use: table =>
        IO.fromEither(
          table
            .show(TableRenderOptions(maxRows = 20, maxWidth = 100))
            .left
            .map(TableReadFailure.apply)
        )

  private def acquired: Resource[IO, (Frame[People], Frame[Teams], FrameRuntime[IO])] =
    for
      peopleRef <- Resource.eval(checked(SourceRef.values("people", "people")))
      teamsRef <- Resource.eval(checked(SourceRef.values("teams", "teams")))
      people = InMemoryFrameSource.rowsBinding[IO, People](
        peopleRef,
        peopleRows,
        batchSize = 2
      )
      teams = InMemoryFrameSource.rowsBinding[IO, Teams](
        teamsRef,
        teamRows,
        batchSize = 2
      )
      runtime <- FrameRuntime.resource(people, teams)
    yield (people.frame, teams.frame, runtime)

  def run: IO[Unit] =
    acquired.use: (people, teams, runtime) =>
      val query: Frame[Result] = people
        .filter: row =>
          row.col("score").isNull ||
            (row.col("score") > Some(1.0)).isTrue
        .withColumn("nextId")(row => row.col("id") + 1)
        .innerJoinUsing(teams, "team")
        .groupBy(row => Tuple1(row.col("region")))
        .aggregate: row =>
          (
            Aggregate.count.as("n"),
            Aggregate.mean(row.col("score")).as("meanScore")
          )
      val (normalized, normalization) = query.normalized
      val leftQuery: Frame[LeftResult] = people
        .leftJoinUsing(teams, "team")
        .select: row =>
          (
            row.col("id"),
            row.col("region")
          )

      for
        _ <- IO.println(normalized.explain)
        _ <- IO.println(s"normalization rules: ${normalization.rules.mkString(", ")}")
        result <- rendered(runtime, normalized)
        _ <- IO.println(result)
        leftResult <- rendered(runtime, leftQuery)
        _ <- IO.println(leftResult)
      yield ()
