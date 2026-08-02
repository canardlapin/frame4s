package frame4s.fs2

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Deferred
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import frame4s.*

class FrameIOStreamingSuite extends munit.FunSuite:
  type Row = (id: Int)

  private val descriptor = summon[SchemaDescriptor[Row]]

  final private case class Probe(
      plans: Ref[IO, Int],
      pulls: Ref[IO, Int],
      activeBatches: Ref[IO, Int],
      maximumActiveBatches: Ref[IO, Int],
      closes: Ref[IO, Int]
  )

  final private class ProbedSource(
      chunks: Vector[Vector[Int]],
      tracker: BufferTracker,
      probe: Probe
  ) extends FrameSource[IO]:
    private val capabilities =
      SourceCapabilities(
        projection = false,
        predicate = false,
        limit = false,
        batchSize = false,
        streaming = true
      )

    def inspect: IO[Either[SourceError, SourceInspection]] =
      IO.pure(Right(SourceInspection(descriptor.schema, capabilities)))

    def plan(request: ScanRequest): IO[Either[SourceError, PlannedScan[IO]]] =
      probe.plans
        .update(_ + 1)
        .as:
          Right(
            PlannedScan(
              descriptor.schema,
              PushdownReceipt(
                request.requestedFeatures,
                Vector.empty,
                request.requestedFeatures,
                descriptor.schema.fields.map(_.name)
              ),
              Stream.emits(chunks).covary[IO].flatMap(scopedBatch)
            )
          )

    private[fs2] def close: IO[Either[SourceError, Unit]] =
      probe.closes.update(_ + 1).as(Right(()))

    private def scopedBatch(values: Vector[Int]): Stream[IO, RecordBatch] =
      Stream
        .bracket(
          makeBatch(values) <*
            probe.pulls.update(_ + 1) <*
            probe.activeBatches
              .updateAndGet(_ + 1)
              .flatMap: active =>
                probe.maximumActiveBatches.update(current => math.max(current, active))
        )(batch => IO(batch.close()) *> probe.activeBatches.update(_ - 1))
        .flatMap(Stream.emit)

    private def makeBatch(values: Vector[Int]): IO[RecordBatch] =
      val built = for
        column <- ColumnArray.int32(values.toArray, tracker = tracker)
        batch <- RecordBatch(descriptor.schema, Vector(column)).leftMap: error =>
          column.close()
          error
      yield batch
      IO.fromEither(built.leftMap(error => SourceFailure(SourceError.Storage(error))))

  private def binding(
      id: String,
      chunks: Vector[Vector[Int]],
      tracker: BufferTracker = new BufferTracker
  ): IO[(SourceBinding[IO, Row], Probe)] =
    for
      plans <- Ref.of[IO, Int](0)
      pulls <- Ref.of[IO, Int](0)
      active <- Ref.of[IO, Int](0)
      maximum <- Ref.of[IO, Int](0)
      closes <- Ref.of[IO, Int](0)
      probe = Probe(plans, pulls, active, maximum, closes)
      reference <- IO.fromEither(
        SourceRef.scan(id, id).leftMap(error => new IllegalArgumentException(error.message))
      )
      source = FrameSource.owningResource(IO(new ProbedSource(chunks, tracker, probe)))
    yield SourceBinding[IO, Row, ProbedSource](reference, source) -> probe

  private def planned[A](result: Either[FrameError, A]): A =
    result.fold(error => fail(error.message), identity)

  test("scan, filter, project, withColumn, and limit emit before source drain"):
    val tracker = new BufferTracker
    val program = binding(
      "incremental",
      Vector.tabulate(100)(index => Vector(index + 1)),
      tracker
    ).flatMap: (source, probe) =>
      val query = planned:
        source.frame
          .filter(row => row.col("id") > 0)
          .select(row => Tuple1(row.col("id").as("id")))
          .withColumn("next")(_.col("id") + 1)
          .limit(1)

      FrameRuntime
        .resource(source)
        .use(_.stream(query).compile.toVector)
        .flatMap: batches =>
          (
            probe.plans.get,
            probe.pulls.get,
            probe.activeBatches.get,
            probe.maximumActiveBatches.get,
            probe.closes.get
          ).tupled.map: (plans, pulls, active, maximum, closes) =>
            assertEquals(batches.map(_.rowCount), Vector(1))
            assertEquals(plans, 1)
            assertEquals(pulls, 1)
            assertEquals(active, 0)
            assertEquals(maximum, 1)
            assertEquals(closes, 1)
            assertEquals(tracker.snapshot.activeOwners, 0)

    program.unsafeToFuture()

  test("limit over union does not pull the right branch"):
    val program = (
      binding("union-left", Vector(Vector(1))),
      binding(
        "union-right",
        Vector(Vector(2))
      )
    ).tupled.flatMap:
      case ((left, leftProbe), (right, rightProbe)) =>
        val query = planned(left.frame.unionAll(right.frame).limit(1))

        FrameRuntime
          .resource(left, right)
          .flatMap(_.streamWithReceipt(query))
          .use: execution =>
            execution.batches.compile.toVector.map(batches => execution.receipt -> batches)
          .flatMap: (receipt, batches) =>
            (
              leftProbe.pulls.get,
              rightProbe.pulls.get,
              leftProbe.activeBatches.get,
              rightProbe.activeBatches.get,
              leftProbe.closes.get,
              rightProbe.closes.get
            ).tupled.map:
              (leftPulls, rightPulls, leftActive, rightActive, leftCloses, rightCloses) =>
                assertEquals(batches.map(_.rowCount), Vector(1))
                assertEquals(
                  receipt.sources.map(_.reference.id.value),
                  Vector("union-left", "union-right")
                )
                assertEquals(leftPulls, 1)
                assertEquals(rightPulls, 0)
                assertEquals(leftActive, 0)
                assertEquals(rightActive, 0)
                assertEquals(leftCloses, 1)
                assertEquals(rightCloses, 1)

    program.unsafeToFuture()

  test("blocking sort drains its source before emitting"):
    val program = binding(
      "blocking-sort",
      Vector(Vector(3), Vector(1), Vector(2))
    ).flatMap: (source, probe) =>
      val query = source.frame.sortBy(_.col("id"))

      FrameRuntime
        .resource(source)
        .use: runtime =>
          IO(
            assertEquals(
              runtime.streamPhysicalExplain(query),
              "ReferenceExecution(mode=blocking, blocking=Sort, operators=Sort>Scan, " +
                "estimatedRows=unknown, fallback=none)"
            )
          ) *> runtime.stream(query).take(1).compile.toVector
        .flatMap: batches =>
          (probe.pulls.get, probe.activeBatches.get, probe.closes.get).tupled.map:
            (pulls, active, closes) =>
              assertEquals(batches.map(_.rowCount), Vector(3))
              assertEquals(pulls, 3)
              assertEquals(active, 0)
              assertEquals(closes, 1)

    program.unsafeToFuture()

  test("incremental execution closes source batches and resources on failure"):
    val program = binding(
      "stream-failure",
      Vector(Vector(Int.MaxValue), Vector(2))
    ).flatMap: (source, probe) =>
      val query = source.frame.withColumn("next")(_.col("id") + 1)

      FrameRuntime
        .resource(source)
        .use(_.stream(query).compile.drain)
        .attempt
        .flatMap:
          case Left(ExecutionFailure(ExecutionError.IntegerOverflow(_, BinaryOperator.Add))) =>
            IO.unit
          case other => IO(fail(s"expected structured overflow, found $other"))
        .flatMap: _ =>
          (probe.pulls.get, probe.activeBatches.get, probe.closes.get).tupled.map:
            (pulls, active, closes) =>
              assertEquals(pulls, 1)
              assertEquals(active, 0)
              assertEquals(closes, 1)

    program.unsafeToFuture()

  test("incremental execution closes source batches and resources on cancellation"):
    val program = binding(
      "stream-cancel",
      Vector(Vector(1), Vector(2))
    ).flatMap: (source, probe) =>
      for
        started <- Deferred[IO, Unit]
        fiber <- FrameRuntime
          .resource(source)
          .use:
            _.stream(source.frame)
              .evalMap(_ => started.complete(()).void *> IO.never)
              .compile
              .drain
          .start
        _ <- started.get
        _ <- fiber.cancel
        pulls <- probe.pulls.get
        active <- probe.activeBatches.get
        closes <- probe.closes.get
      yield
        assertEquals(pulls, 1)
        assertEquals(active, 0)
        assertEquals(closes, 1)

    program.unsafeToFuture()
