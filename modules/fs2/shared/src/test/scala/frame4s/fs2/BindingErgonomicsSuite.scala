package frame4s.fs2

import cats.effect.Deferred
import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import frame4s.*
import scala.compiletime.testing.typeCheckErrors

class BindingErgonomicsSuite extends munit.FunSuite:
  type Input = (id: Int, label: String, score: Option[Double])
  type Selected = (label: String, score: Option[Double])

  private val rows = Vector(
    (id = 1, label = "Ada", score = Some(9.5)),
    (id = 2, label = "Lin", score = None),
    (id = 3, label = "Edsger", score = Some(8.0))
  )

  private def get[A](result: Either[?, A]): A =
    result.fold(error => fail(error.toString), identity)

  private def selected(frame: Frame[Input], minimum: Int): Frame[Selected] =
    frame
      .filter(row => row.col("id") > minimum)
      .select(row => (row.col("label"), row.col("score")))

  test("single-source constructors use stable kind-correct identities"):
    val memoryA = InMemoryFrameSource.rows[IO, Input](rows)
    val memoryB = InMemoryFrameSource.rows[IO, Input](rows.reverse)
    val csv = CsvFrameSource.binding[IO, Input](
      "id,label,score\n1,Ada,9.5\n2,Lin,\n"
    )
    val tsv = TsvFrameSource.binding[IO, Input](
      "id\tlabel\tscore\n1\tAda\t9.5\n2\tLin\t\n"
    )

    assertEquals(memoryA.reference, memoryB.reference)
    assertEquals(memoryA.reference.kind, SourceKind.Values)
    assertEquals(memoryA.reference.order, OrderGuarantee.Stable)
    assertEquals(csv.reference.kind, SourceKind.Scan)
    assertEquals(tsv.reference, csv.reference)
    assertEquals(memoryA.identity, BindingIdentity.SingleSource)
    assertEquals(csv.identity, BindingIdentity.SingleSource)

  test("collect, stream, and render are exact runtime expansions"):
    val binding = InMemoryFrameSource.rows[IO, Input](rows, batchSize = 2)
    val query = selected(binding.frame, 1)

    val conciseCollect =
      binding
        .collect(query)
        .use(table => IO.fromEither(table.column("label").leftMap(TableReadFailure.apply)))
    val expandedCollect =
      FrameRuntime
        .resource(binding)
        .flatMap(_.collect(query))
        .use(table => IO.fromEither(table.column("label").leftMap(TableReadFailure.apply)))

    def labels(stream: _root_.fs2.Stream[IO, RecordBatch]): IO[Vector[String]] =
      stream
        .evalMap: batch =>
          IO.fromEither:
            batch
              .column("label")
              .leftMap(error => TableReadFailure(TableReadError.Storage(error)))
              .flatMap: column =>
                Vector
                  .tabulate(batch.rowCount)(column.scalar)
                  .sequence
                  .leftMap(error => TableReadFailure(TableReadError.Storage(error)))
                  .flatMap: values =>
                    values.traverse:
                      case ScalarValue.Utf8(value) => Right(value)
                      case other                   =>
                        Left(
                          TableReadFailure(
                            TableReadError.Storage(
                              StorageError.Unexpected(s"expected Utf8, found $other")
                            )
                          )
                        )
        .compile
        .toVector
        .map(_.flatten)

    val conciseStream = labels(binding.stream(query))
    val expandedStream =
      labels(_root_.fs2.Stream.resource(FrameRuntime.resource(binding)).flatMap(_.stream(query)))
    val options = TableRenderOptions(maxRows = 5, maxWidth = 60)
    val conciseRender = binding.render(query, options)
    val expandedRender =
      FrameRuntime
        .resource(binding)
        .flatMap(_.collect(query))
        .use(table => IO.fromEither(table.show(options).leftMap(TableReadFailure.apply)))

    (
      conciseCollect,
      expandedCollect,
      conciseStream,
      expandedStream,
      conciseRender,
      expandedRender
    ).tupled
      .map: (collected, expanded, streamed, expandedBatches, rendered, expandedText) =>
        assertEquals(collected, Vector("Lin", "Edsger"))
        assertEquals(expanded, collected)
        assertEquals(streamed, collected)
        assertEquals(expandedBatches, collected)
        assertEquals(expandedText, rendered)
      .unsafeToFuture()

  test("one query remains reusable, conditional, and independently applicable"):
    val first = InMemoryFrameSource.rows[IO, Input](rows)
    val second = InMemoryFrameSource.rows[IO, Input](rows.reverse)
    val firstQuery = selected(first.frame, 1)
    val conditional = if rows.nonEmpty then firstQuery
    else first.frame.select(row => (row.col("label"), row.col("score")))
    val secondQuery = selected(second.frame, 1)
    val firstRun = first.render(conditional)
    val repeated = first.render(conditional)
    val secondRun = second.render(secondQuery)

    (firstRun, repeated, secondRun).tupled
      .map: (one, two, other) =>
        assertEquals(two, one)
        assert(other.contains("Edsger"))
        assert(other.contains("Lin"))
      .unsafeToFuture()

  test("single-source identities are rejected before multi-binding acquisition"):
    val left = InMemoryFrameSource.rows[IO, Input](rows)
    val right = InMemoryFrameSource.rows[IO, Input](rows.reverse)

    FrameRuntime
      .resource(left, right)
      .use(_ => IO.unit)
      .attempt
      .map:
        case Left(
              RuntimeBindingFailure(
                RuntimeBindingError.SingleSourceIdentityInMultiBinding(ids)
              )
            ) =>
          assertEquals(ids, Vector(left.reference.id, right.reference.id))
        case other => fail(s"expected single-source identity rejection, found $other")
      .unsafeToFuture()

  test("binding conveniences preserve exact-once finalization across lifecycle outcomes"):
    val input = get(Table.fromRows[Input](rows, batchSize = 1))

    def countedBinding(closes: Ref[IO, Int]): SourceBinding[IO, Input] =
      val source =
        Resource.make(
          IO(InMemoryFrameSource[IO](input.schema, input.batches))
        )(_ => closes.update(_ + 1))
      SourceBinding.singleSource(SourceRef.singleSourceValues, source)

    Ref
      .of[IO, Int](0)
      .flatMap: closes =>
        val binding = countedBinding(closes)
        val query = selected(binding.frame, 0)
        val success = binding.collect(query).use(_ => IO.unit)
        val renderFailure =
          binding
            .render(query, TableRenderOptions(maxWidth = 1))
            .attempt
            .flatMap:
              case Left(TableReadFailure(TableReadError.InvalidRenderOptions(_))) => IO.unit
              case other => IO(fail(s"expected structured render failure, found $other"))
        val early = binding.stream(query).take(1).compile.drain
        val canceled =
          for
            started <- Deferred[IO, Unit]
            fiber <- binding
              .stream(query)
              .evalMap(_ => started.complete(()).void *> IO.never)
              .compile
              .drain
              .start
            _ <- started.get
            _ <- fiber.cancel
          yield ()

        success *> renderFailure *> early *> canceled *> closes.get.map(count =>
          assertEquals(count, 4)
        )
      .guarantee(IO(input.close()))
      .unsafeToFuture()

  test("acquisition, decode, and missing-binding failures remain structured"):
    val reference = get(SourceRef.scan("failing", "failing"))

    final class InspectFailure extends FrameSource[IO]:
      def inspect: IO[Either[SourceError, SourceInspection]] =
        IO.pure(Left(SourceError.Open("unavailable")))
      def plan(request: ScanRequest): IO[Either[SourceError, PlannedScan[IO]]] =
        IO.pure(Left(SourceError.Open("unavailable")))
      private[fs2] def close: IO[Either[SourceError, Unit]] = IO.pure(Right(()))

    Ref
      .of[IO, Int](0)
      .flatMap: closes =>
        val failing = SourceBinding[IO, Input, InspectFailure](
          reference,
          Resource.make(IO(new InspectFailure))(_ => closes.update(_ + 1))
        )
        val acquisition = failing.collect(failing.frame).use(_ => IO.unit).attempt

        val malformed = CsvFrameSource.binding[IO, Input](
          "id,label,score\nnot-an-int,Ada,9.5\n"
        )
        val decode = malformed.collect(malformed.frame).use(_ => IO.unit).attempt

        val otherReference = get(SourceRef.scan("other", "other"))
        val unboundFrame = Frame.scan[Input](otherReference)
        val bound = InMemoryFrameSource.rows[IO, Input](rows)
        val missing = bound.collect(unboundFrame).use(_ => IO.unit).attempt

        (acquisition, decode, missing, closes.get).tupled.map:
          case (
                Left(RuntimeBindingFailure(RuntimeBindingError.Source(_, SourceError.Open(_)))),
                Left(
                  RuntimeBindingFailure(
                    RuntimeBindingError.Source(_, SourceError.Decode(_, _, _, _))
                  )
                ),
                Left(RuntimeBindingFailure(RuntimeBindingError.MissingSource(id))),
                closeCount
              ) =>
            assertEquals(id, otherReference.id)
            assertEquals(closeCount, 1)
          case other => fail(s"expected structured binding failures, found $other")
      .unsafeToFuture()

  test("SourceBinding does not expose a second transformation algebra"):
    val errors = typeCheckErrors("""
      import cats.effect.IO
      import frame4s.*
      import frame4s.fs2.*
      type Input = (id: Int)
      val binding = InMemoryFrameSource.rows[IO, Input](Vector((id = 1)))
      binding.filter(row => row.col("id") > 0)
    """)
    val message = errors.headOption.fold(fail("expected a compile error"))(_.message)
    assert(message.contains("filter"), message)
    assert(message.contains("not a member"), message)
