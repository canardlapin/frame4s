package frame4s.fs2

import cats.effect.Deferred
import cats.effect.IO
import cats.effect.Resource
import cats.effect.kernel.Outcome
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import frame4s.*

class AdapterFailureSuite extends munit.FunSuite:
  type Input = (id: Int)

  private val schema = summon[SchemaDescriptor[Input]].schema
  private val options = CsvReadOptions(schema, batchSize = 1)

  private def drain(source: FrameSource[IO]): IO[Unit] =
    source
      .plan(ScanRequest())
      .flatMap:
        case Left(error) => IO.raiseError(SourceFailure(error))
        case Right(scan) => scan.batches.compile.drain

  private def sourceReference(id: String): SourceRef =
    SourceRef.scan(id, id).fold(error => fail(error.message), identity)

  private def assertCause(error: SourceError, expected: Throwable): Unit =
    assert(error.cause.exists(_ eq expected), s"missing original cause in $error")

  private def assertCause(error: SinkError, expected: Throwable): Unit =
    assert(error.cause.exists(_ eq expected), s"missing original cause in $error")

  test("portable character and byte sources classify upstream and malformed UTF-8 failures"):
    val upstream = new IllegalStateException("credential=source-secret")
    val characters =
      Stream.emits("id\n1\n".toVector).covary[IO] ++ Stream.raiseError[IO](upstream)
    val malformed = Stream
      .emits(
        Array[Byte]('i'.toByte, 'd'.toByte, '\n'.toByte, 0xc3.toByte, 0x28.toByte, '\n'.toByte)
      )
      .covary[IO]

    val program = for
      characterResult <- CsvFrameSource.characters(characters, options).use(drain).attempt
      byteResult <- CsvFrameSource.bytes(malformed, options).use(drain).attempt
    yield
      characterResult match
        case Left(failure @ SourceFailure(error @ SourceError.Upstream(cause))) =>
          assert(cause eq upstream)
          assertCause(error, upstream)
          assert(failure.getCause eq upstream)
        case other => fail(s"expected structured upstream source failure, found $other")

      byteResult match
        case Left(failure @ SourceFailure(error @ SourceError.InvalidUtf8(offset, cause))) =>
          assertEquals(offset, 4L)
          assertCause(error, cause)
          assert(failure.getCause eq cause)
        case other => fail(s"expected structured UTF-8 source failure, found $other")

    program.unsafeToFuture()

  test("source acquisition and finalization classify ordinary nonfatal failures"):
    val openCause = new IllegalStateException("credential=open-secret")
    val closeCause = new IllegalStateException("credential=close-secret")
    val unavailable: IO[FrameSource[IO]] = IO.raiseError(openCause)
    val closesBadly = new FrameSource[IO]:
      def inspect: IO[Either[SourceError, SourceInspection]] =
        IO.pure(
          Right(
            SourceInspection(
              schema,
              SourceCapabilities(false, false, false, false, streaming = true)
            )
          )
        )

      def plan(request: ScanRequest): IO[Either[SourceError, PlannedScan[IO]]] =
        IO.pure(
          Right(
            PlannedScan(
              schema,
              PushdownReceipt(
                request.requestedFeatures,
                Vector.empty,
                request.requestedFeatures,
                Vector("id")
              ),
              Stream.empty
            )
          )
        )

      private[fs2] def close: IO[Either[SourceError, Unit]] = IO.raiseError(closeCause)

    val program = for
      openResult <- FrameSource.owningResource(unavailable).use(_ => IO.unit).attempt
      closeResult <- FrameSource
        .owningResource(IO.pure(closesBadly: FrameSource[IO]))
        .use(_ => IO.unit)
        .attempt
    yield
      openResult match
        case Left(failure @ SourceFailure(error @ SourceError.Open(cause))) =>
          assertCause(error, openCause)
          assert(failure.getCause eq openCause)
          assert(cause eq openCause)
        case other => fail(s"expected structured source-open failure, found $other")

      closeResult match
        case Left(failure @ SourceFailure(error @ SourceError.Close(cause))) =>
          assertCause(error, closeCause)
          assert(failure.getCause eq closeCause)
          assert(cause eq closeCause)
        case other => fail(s"expected structured source-close failure, found $other")

    program.unsafeToFuture()

  test("strict UTF-8 rejects every forbidden encoding class"):
    val prefix = Array[Byte]('i'.toByte, 'd'.toByte, '\n'.toByte)
    val cases = Vector(
      "invalid leading byte" -> Array[Byte](0x80.toByte),
      "overlong encoding" -> Array[Byte](0xe0.toByte, 0x80.toByte, 0x80.toByte),
      "surrogate code point" -> Array[Byte](0xed.toByte, 0xa0.toByte, 0x80.toByte),
      "out-of-range code point" -> Array[Byte](0xf4.toByte, 0x90.toByte, 0x80.toByte, 0x80.toByte),
      "truncated code point" -> Array[Byte](0xe2.toByte, 0x82.toByte)
    )

    cases
      .traverse: (label, suffix) =>
        val bytes = Stream.emits(prefix ++ suffix).covary[IO]
        CsvFrameSource.bytes(bytes, options).use(drain).attempt.map(label -> _)
      .map:
        _.foreach:
          case (
                label,
                Left(failure @ SourceFailure(error @ SourceError.InvalidUtf8(offset, cause)))
              ) =>
            assert(offset >= prefix.length.toLong, label)
            assert(error.cause.exists(_ eq cause), label)
            assert(failure.getCause eq cause, label)
          case (label, other) => fail(s"$label was not rejected as malformed UTF-8: $other")
      .unsafeToFuture()

  test("CSV and TSV sinks return upstream failures and retain their causes"):
    val cause = new IllegalStateException("credential=sink-secret")
    val csv = new CsvFrameSink[IO]().write(schema, Stream.raiseError[IO](cause))
    val tsv = new TsvFrameSink[IO]().write(schema, Stream.raiseError[IO](cause))

    (csv, tsv).tupled
      .map: results =>
        Vector(results._1, results._2).foreach:
          case Left(error @ SinkError.Upstream(actual)) =>
            assert(actual eq cause)
            assertCause(error, cause)
          case other => fail(s"expected structured sink-upstream failure, found $other")
      .unsafeToFuture()

  test("sink write and close classifications survive the total Either boundary"):
    val writeCause = new IllegalStateException("write")
    val closeCause = new IllegalStateException("close")

    def classified(cause: Throwable, error: Throwable => SinkError) =
      AdapterFailureBoundary.sinkEither(
        AdapterFailureBoundary
          .sinkEffect(IO.raiseError[Unit](cause), error)
          .map(_ => Right(()): Either[SinkError, Unit]),
        SinkError.Upstream.apply
      )

    (
      classified(writeCause, SinkError.Write.apply),
      classified(closeCause, SinkError.Close.apply)
    ).tupled
      .map: (writeResult, closeResult) =>
        writeResult match
          case Left(error @ SinkError.Write(cause)) =>
            assert(cause eq writeCause)
            assertCause(error, writeCause)
          case other => fail(s"expected structured sink-write failure, found $other")
        closeResult match
          case Left(error @ SinkError.Close(cause)) =>
            assert(cause eq closeCause)
            assertCause(error, closeCause)
          case other => fail(s"expected structured sink-close failure, found $other")
      .unsafeToFuture()

  test("adapter messages are bounded and do not render cause text"):
    val secret = "credential=" + ("private-token-" * 100)
    val cause = new IllegalStateException(secret + "\ncontrol\u0000")
    val source = SourceError.Upstream(cause)
    val sink = SinkError.Upstream(cause)
    val sourceFailure = SourceFailure(source)
    val sinkFailure = SinkFailure(sink)

    List(source.message, source.toString, sourceFailure.getMessage).foreach: rendered =>
      assert(rendered.length <= 256)
      assert(!rendered.contains(secret))
      assert(!rendered.contains("\u0000"))
    List(sink.message, sink.toString, sinkFailure.getMessage).foreach: rendered =>
      assert(rendered.length <= 256)
      assert(!rendered.contains(secret))
      assert(!rendered.contains("\u0000"))
    assert(sourceFailure.getCause eq cause)
    assert(sinkFailure.getCause eq cause)

  test(
    "FrameRuntime translates raw source-stream failures without losing source identity or cause"
  ):
    val cause = new IllegalStateException("credential=runtime-secret")
    val reference = sourceReference("runtime-upstream")
    val source = new FrameSource[IO]:
      def inspect: IO[Either[SourceError, SourceInspection]] =
        IO.pure(
          Right(
            SourceInspection(
              schema,
              SourceCapabilities(false, false, false, false, streaming = true)
            )
          )
        )

      def plan(request: ScanRequest): IO[Either[SourceError, PlannedScan[IO]]] =
        IO.pure(
          Right(
            PlannedScan(
              schema,
              PushdownReceipt(
                request.requestedFeatures,
                Vector.empty,
                request.requestedFeatures,
                Vector("id")
              ),
              Stream.raiseError[IO](cause)
            )
          )
        )

      private[fs2] def close: IO[Either[SourceError, Unit]] = IO.pure(Right(()))

    val binding = SourceBinding[IO, Input, FrameSource[IO]](
      reference,
      Resource.pure[IO, FrameSource[IO]](source)
    )

    FrameRuntime
      .resource(binding)
      .flatMap(_.collect(binding.frame))
      .use(_ => IO.unit)
      .attempt
      .map:
        case Left(
              failure @ RuntimeBindingFailure(
                RuntimeBindingError.Source(id, error @ SourceError.Upstream(actual))
              )
            ) =>
          assertEquals(id, reference.id)
          assert(actual eq cause)
          assertCause(error, cause)
          assert(failure.getCause eq cause)
        case other => fail(s"expected structured runtime source failure, found $other")
      .unsafeToFuture()

  test("FrameRuntime classifies acquisition, inspection, planning, and close failures uniformly"):
    val capabilities = SourceCapabilities(false, false, false, false, streaming = true)
    val inspection = SourceInspection(schema, capabilities)

    def planned(request: ScanRequest): PlannedScan[IO] =
      PlannedScan(
        schema,
        PushdownReceipt(
          request.requestedFeatures,
          Vector.empty,
          request.requestedFeatures,
          Vector("id")
        ),
        Stream.empty
      )

    final class BoundarySource(
        inspectEffect: IO[Either[SourceError, SourceInspection]],
        planEffect: ScanRequest => IO[Either[SourceError, PlannedScan[IO]]],
        closeEffect: IO[Either[SourceError, Unit]]
    ) extends FrameSource[IO]:
      def inspect: IO[Either[SourceError, SourceInspection]] = inspectEffect
      def plan(request: ScanRequest): IO[Either[SourceError, PlannedScan[IO]]] =
        planEffect(request)
      private[fs2] def close: IO[Either[SourceError, Unit]] = closeEffect

    def valid(close: IO[Either[SourceError, Unit]]): BoundarySource =
      new BoundarySource(
        IO.pure(Right(inspection)),
        request => IO.pure(Right(planned(request))),
        close
      )

    def binding(
        id: String,
        resource: Resource[IO, FrameSource[IO]]
    ): SourceBinding[IO, Input] =
      SourceBinding[IO, Input, FrameSource[IO]](sourceReference(id), resource)

    def acquireOnly(value: SourceBinding[IO, Input]): IO[Unit] =
      FrameRuntime.resource(value).use(_ => IO.unit)

    def execute(value: SourceBinding[IO, Input]): IO[Unit] =
      FrameRuntime.resource(value).flatMap(_.collect(value.frame)).use(_ => IO.unit)

    val openCause = new IllegalStateException("open")
    val inspectCause = new IllegalStateException("inspect")
    val planCause = new IllegalStateException("plan")
    val closeCause = new IllegalStateException("close")
    val open = binding(
      "runtime-open",
      Resource.eval(IO.raiseError[FrameSource[IO]](openCause))
    )
    val inspect = binding(
      "runtime-inspect",
      Resource.pure(
        new BoundarySource(
          IO.raiseError(inspectCause),
          request => IO.pure(Right(planned(request))),
          IO.pure(Right(()))
        ): FrameSource[IO]
      )
    )
    val plan = binding(
      "runtime-plan",
      Resource.pure(
        new BoundarySource(
          IO.pure(Right(inspection)),
          _ => IO.raiseError(planCause),
          IO.pure(Right(()))
        ): FrameSource[IO]
      )
    )
    val close = binding(
      "runtime-close",
      FrameSource.owningResource(
        IO.pure(valid(IO.raiseError(closeCause)): FrameSource[IO])
      )
    )

    val program = for
      openResult <- acquireOnly(open).attempt
      inspectResult <- acquireOnly(inspect).attempt
      planResult <- execute(plan).attempt
      closeResult <- acquireOnly(close).attempt
    yield
      openResult match
        case Left(failure @ RuntimeBindingFailure(RuntimeBindingError.Source(id, error))) =>
          assertEquals(id, open.reference.id)
          assert(error.isInstanceOf[SourceError.Open])
          assert(failure.getCause eq openCause)
        case other => fail(s"expected runtime open classification, found $other")
      inspectResult match
        case Left(failure @ RuntimeBindingFailure(RuntimeBindingError.Source(id, error))) =>
          assertEquals(id, inspect.reference.id)
          assert(error.isInstanceOf[SourceError.Open])
          assert(failure.getCause eq inspectCause)
        case other => fail(s"expected runtime inspect classification, found $other")
      planResult match
        case Left(failure @ RuntimeBindingFailure(RuntimeBindingError.Source(id, error))) =>
          assertEquals(id, plan.reference.id)
          assert(error.isInstanceOf[SourceError.Upstream])
          assert(failure.getCause eq planCause)
        case other => fail(s"expected runtime plan classification, found $other")
      closeResult match
        case Left(failure @ RuntimeBindingFailure(RuntimeBindingError.Source(id, error))) =>
          assertEquals(id, close.reference.id)
          assert(error.isInstanceOf[SourceError.Close])
          assert(failure.getCause eq closeCause)
        case other => fail(s"expected runtime close classification, found $other")

    program.unsafeToFuture()

  test(
    "fatal errors are outside the ordinary classifier and sink cancellation remains cancellation"
  ):
    val fatal = new LinkageError("fatal-source")
    val sink = new CsvFrameSink[IO]()

    val program = for
      started <- Deferred[IO, Unit]
      fiber <- sink
        .write(
          schema,
          Stream.eval(started.complete(())).drain ++ Stream.never[IO]
        )
        .start
      _ <- started.get
      _ <- fiber.cancel
      canceled <- fiber.join
    yield
      assert(!AdapterFailureBoundary.isOrdinary(fatal))
      canceled match
        case Outcome.Canceled() => ()
        case other              => fail(s"expected canceled sink fiber, found $other")

    program.unsafeToFuture()
