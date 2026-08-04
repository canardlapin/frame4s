package example

import cats.effect.IO
import cats.effect.Resource
import fs2.Stream
import fs2.io.file.Path
import frame4s.*
import frame4s.fs2.*

/** Downstream compiling specimens for the ergonomic API ratified by ADR-0004.
  *
  * This project is outside the `frame4s` packages and uses only public API. The methods exercise
  * the same `Frame` algebra through concise single-source execution, explicit multi-source
  * execution, owned collection, streaming, and receipt-bearing collection.
  */
object ApiContractCourt:
  type People = (id: Int, name: String, score: Option[Double])
  type Selected = (name: String, score: Option[Double])
  type Computed = (nextId: Int)
  type RightRows = (key: Int, label: String)
  type Joined = (id: Int, name: String, score: Option[Double], key: Int, label: String)
  type ScoreRows = (team: String, score: Option[Double])
  type TeamStats = (team: String, meanScore: Option[Double])

  def reusable(frame: Frame[People], minimumId: Int): Frame[Selected] =
    frame
      .filter(row => row.col("id") > minimumId)
      .select(row => (row.col("name"), row.col("score")))

  def conditional(frame: Frame[People], positiveOnly: Boolean): Frame[People] =
    if positiveOnly then frame.filter(row => row.col("id") > 0)
    else frame

  def detached(binding: SourceBinding[IO, People]): IO[String] =
    binding.render(reusable(binding.frame, 0))

  def owned(binding: SourceBinding[IO, People]): Resource[IO, Table[Selected]] =
    binding.collect(reusable(binding.frame, 0))

  def streamed(binding: SourceBinding[IO, People]): Stream[IO, RecordBatch] =
    binding.stream(reusable(binding.frame, 0))

  def repeated(binding: SourceBinding[IO, People]): IO[(String, String)] =
    val rendered = detached(binding)
    rendered.flatMap(first => rendered.map(second => (first, second)))

  def joinedWithReceipt(
      left: SourceBinding[IO, People],
      right: SourceBinding[IO, RightRows]
  ): Resource[IO, MaterializedExecution[Joined]] =
    val query = left.frame.innerJoin(right.frame): (lhs, rhs) =>
      lhs.col("id") === rhs.col("key")
    FrameRuntime.resource(left, right).flatMap(_.collectWithReceipt(query))

  def nullableCsvAggregation(
      binding: SourceBinding[IO, ScoreRows]
  ): Resource[IO, Table[TeamStats]] =
    val query: Frame[TeamStats] = binding.frame
      .filter(row => (row.col("score") > Some(0.0)).isTrue)
      .groupBy(row => Tuple1(row.col("team")))
      .aggregate: row =>
        Tuple1(Aggregate.mean(row.col("score")).as("meanScore"))
    binding.collect(query)

  def constructorCalls(
      rows: Vector[People],
      path: Path
  ): (
      SourceBinding[IO, People],
      SourceBinding[IO, People],
      SourceBinding[IO, People],
      SourceBinding[IO, People],
      SourceBinding[IO, People]
  ) =
    (
      InMemoryFrameSource.rows[IO, People](rows),
      CsvFrameSource.binding[IO, People]("id,name,score\n1,Ada,9.5\n"),
      TsvFrameSource.binding[IO, People]("id\tname\tscore\n1\tAda\t9.5\n"),
      CsvPathSource.binding[IO, People](path),
      TsvPathSource.binding[IO, People](path)
    )

  def explicitJoinBindings(
      peopleRef: SourceRef,
      rightRef: SourceRef,
      people: Vector[People],
      right: Vector[RightRows]
  ): (SourceBinding[IO, People], SourceBinding[IO, RightRows]) =
    (
      InMemoryFrameSource.rowsBinding[IO, People](peopleRef, people),
      InMemoryFrameSource.rowsBinding[IO, RightRows](rightRef, right)
    )

  def inferredProjection(frame: Frame[People]): Frame[Selected] =
    frame.select(row => (row.col("name"), row.col("score")))

  def namedComputation(frame: Frame[People]): Frame[Computed] =
    frame.select(row => Tuple1((row.col("id") + 1).as("nextId")))

  def inferredCollectedColumn(binding: SourceBinding[IO, People]): IO[Vector[String]] =
    val query = binding.frame
      .filter(row => row.col("id") > 0)
      .select(row => (row.col("name"), row.col("score")))
    binding
      .collect(query)
      .use: table =>
        IO.fromEither(table.column("name").left.map(TableReadFailure.apply))

  def inferredAggregateColumn(binding: SourceBinding[IO, People]): IO[Vector[Long]] =
    val query = binding.frame
      .groupBy(row => Tuple1(row.col("name")))
      .aggregate(_ => Tuple1(Aggregate.count.as("n")))
    binding
      .collect(query)
      .use: table =>
        IO.fromEither(table.column("n").left.map(TableReadFailure.apply))
