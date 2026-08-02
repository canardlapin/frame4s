package frame4s.fs2

import cats.effect.Deferred
import cats.effect.IO
import cats.effect.Ref
import cats.effect.Resource
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import frame4s.*

class FrameRuntimeSuite extends munit.FunSuite:
  type Input = (id: Int)

  private val schema = summon[SchemaDescriptor[Input]].schema

  private def storage[A](result: Either[StorageError, A]): A =
    result.fold(error => fail(error.message), identity)

  private def reference: SourceRef =
    SourceRef.values("input", "input").fold(error => fail(error.message), identity)

  private def frame: Frame[Input] =
    Frame.values[Input](reference).fold(error => fail(error.message), identity)

  private def table(tracker: BufferTracker, values: Vector[Array[Int]]): Table[Input] =
    val batches = values.map: input =>
      storage:
        RecordBatch(
          schema,
          Vector(storage(ColumnArray.int32(input, tracker = tracker)))
        )
    storage(Table[Input](batches))

  test("stream scopes each batch and preserves source ownership"):
    val tracker = new BufferTracker
    val input = table(tracker, Vector(Array(1, 2), Array(3)))
    val runtime = FrameRuntime[IO](ReferenceSources.empty.bind(reference, input))

    runtime
      .stream(frame)
      .evalMap: batch =>
        IO:
          val column = storage(batch.column("id"))
          Vector.tabulate(batch.rowCount)(index => storage(column.scalar(index)))
      .compile
      .toVector
      .map(_.flatten)
      .flatMap: values =>
        IO:
          assertEquals(values, Vector(1, 2, 3).map(ScalarValue.Int32.apply))
          assertEquals(tracker.snapshot.activeOwners, 2)
          assertEquals(tracker.snapshot.activeViews, 2)
          input.close()
          assertEquals(tracker.snapshot.activeOwners, 0)
      .unsafeToFuture()

  test("early termination and cancellation release cursor and batch leases"):
    val tracker = new BufferTracker
    val input = table(tracker, Vector(Array(1), Array(2)))
    val runtime = FrameRuntime[IO](ReferenceSources.empty.bind(reference, input))

    val canceled = for
      started <- Deferred[IO, Unit]
      fiber <- runtime
        .stream(frame)
        .evalMap(_ => started.complete(()).void *> IO.never)
        .compile
        .drain
        .start
      _ <- started.get
      _ <- fiber.cancel
    yield ()

    (
      runtime.stream(frame).take(1).compile.drain *>
        IO(assertEquals(tracker.snapshot.activeViews, 2)) *>
        canceled *>
        IO:
          assertEquals(tracker.snapshot.activeOwners, 2)
          assertEquals(tracker.snapshot.activeViews, 2)
          input.close()
          assertEquals(tracker.snapshot.activeOwners, 0)
    ).unsafeToFuture()

  test("constructing a stream is effect-free and the stream is reusable"):
    val tracker = new BufferTracker
    val input = table(tracker, Vector(Array(1, 2)))
    val runtime = FrameRuntime[IO](ReferenceSources.empty.bind(reference, input))

    val before = tracker.snapshot.activeViews
    val stream = runtime.stream(frame)
    assertEquals(
      tracker.snapshot.activeViews,
      before,
      "building a Stream must not open the execution cursor"
    )

    // An fs2 Stream is a description, so the same value must run more than once.
    (
      stream.compile.toVector
        .flatMap: first =>
          stream.compile.toVector.map(second => (first.map(_.rowCount), second.map(_.rowCount)))
        .flatMap: (first, second) =>
          IO:
            assertEquals(first, second)
            input.close()
      )
      .unsafeToFuture()

  test("collect exposes Table only inside Resource scope"):
    val tracker = new BufferTracker
    val input = table(tracker, Vector(Array(1, 2), Array(3)))
    val runtime = FrameRuntime[IO](ReferenceSources.empty.bind(reference, input))
    var materialized: Option[Table[Input]] = None

    runtime
      .collect(frame)
      .use: output =>
        IO:
          materialized = Some(output)
          assertEquals(output.rowCount, 3L)
          assert(!output.isClosed)
      .flatMap: _ =>
        IO:
          assert(materialized.exists(_.isClosed))
          assertEquals(tracker.snapshot.activeOwners, 2)
          input.close()
          assertEquals(tracker.snapshot.activeOwners, 0)
      .unsafeToFuture()

  test("failed collection closes acquired output and source leases"):
    val tracker = new BufferTracker
    val input = table(tracker, Vector(Array(Int.MaxValue), Array(2)))
    val runtime = FrameRuntime[IO](ReferenceSources.empty.bind(reference, input))
    val query = frame.withColumn("next")(_.col("id") + Expr.literal(1))

    runtime
      .collect(query)
      .use(_ => IO.unit)
      .attempt
      .flatMap: attempted =>
        IO:
          attempted match
            case Left(ExecutionFailure(ExecutionError.IntegerOverflow(_, BinaryOperator.Add))) =>
              ()
            case other => fail(s"expected structured overflow failure, found $other")

          assertEquals(tracker.snapshot.activeOwners, 2)
          assertEquals(tracker.snapshot.activeViews, 2)
          input.close()
          assertEquals(tracker.snapshot.activeOwners, 0)
      .unsafeToFuture()

  test("collect surfaces the engine decision in the execution receipt"):
    val tracker = new BufferTracker
    val input = table(tracker, Vector(Array(1, 2, 3)))
    val runtime = FrameRuntime[IO](ReferenceSources.empty.bind(reference, input))
    val supported = frame.filter(row => row.col("id") > 1)

    def ids(output: Table[Input]): Vector[ScalarValue] =
      output.batches.flatMap: batch =>
        val column = storage(batch.column("id"))
        Vector.tabulate(batch.rowCount)(index => storage(column.scalar(index)))

    runtime
      .collectWithReceipt(supported)
      .use: execution =>
        IO:
          val engine = execution.receipt.engine.getOrElse(fail("missing engine receipt"))
          // A silent decline here would make every runtime comparison vacuous, because both
          // sides would be the reference interpreter.
          assertEquals(engine.backend, "columnar")
          assertEquals(engine.fallback, None)
          assert(engine.physicalPlan.contains("FilterSelection"), engine.physicalPlan)
          assertEquals(ids(execution.table), Vector(2, 3).map(ScalarValue.Int32.apply))
      .flatMap: _ =>
        runtime
          .collectWithReceipt(frame)
          .use: execution =>
            IO:
              val engine = execution.receipt.engine.getOrElse(fail("missing engine receipt"))
              assertEquals(engine.backend, "reference")
              assert(
                engine.fallback.exists(_.contains("unsupported logical shape")),
                engine.fallback
              )
              assertEquals(ids(execution.table), Vector(1, 2, 3).map(ScalarValue.Int32.apply))
      .flatMap: _ =>
        IO:
          input.close()
          assertEquals(tracker.snapshot.activeOwners, 0)
      .unsafeToFuture()

  test("physical explain names the selected backend and forbids fallback"):
    val tracker = new BufferTracker
    val input = table(tracker, Vector(Array(1)))
    val runtime = FrameRuntime[IO](ReferenceSources.empty.bind(reference, input))

    assertEquals(
      runtime.physicalExplain(frame),
      "ReferenceExecution(mode=streaming, blocking=none, operators=Values, estimatedRows=unknown, fallback=none)"
    )
    input.close()

  test("union and existential joins release execution leases on cancellation"):
    type Right = (key: Int)
    val leftTracker = new BufferTracker
    val rightTracker = new BufferTracker
    val leftRef =
      SourceRef.values("cancel-left", "cancel-left").fold(error => fail(error.message), identity)
    val rightRef =
      SourceRef.values("cancel-right", "cancel-right").fold(error => fail(error.message), identity)
    val leftTable = table(leftTracker, Vector(Array(1, 2)))
    val rightTable = Table
      .fromRows[Right](Vector((key = 2)))
      .fold(error => fail(error.message), identity)
    val sameSchemaRight = table(rightTracker, Vector(Array(3, 4)))
    val left = Frame.values[Input](leftRef).fold(error => fail(error.message), identity)
    val right = Frame.values[Right](rightRef).fold(error => fail(error.message), identity)
    val same = Frame.values[Input](rightRef).fold(error => fail(error.message), identity)
    val sources = ReferenceSources.empty
      .bind(leftRef, leftTable)
      .bind(rightRef, rightTable)
    val unionSources = ReferenceSources.empty
      .bind(leftRef, leftTable)
      .bind(rightRef, sameSchemaRight)

    def cancelAfterFirstBatch[A](stream: _root_.fs2.Stream[IO, A]): IO[Unit] =
      for
        started <- Deferred[IO, Unit]
        fiber <- stream
          .evalMap(_ => started.complete(()).void *> IO.never)
          .compile
          .drain
          .start
        _ <- started.get
        _ <- fiber.cancel
      yield ()

    val semi = left.semiJoin(right)((lhs, rhs) => lhs.col("id") === rhs.col("key"))
    val anti = left.antiJoin(right)((lhs, rhs) => lhs.col("id") === rhs.col("key"))
    val union = left.unionAll(same)
    val joins = FrameRuntime[IO](sources)
    val unions = FrameRuntime[IO](unionSources)
    val leftViews = leftTracker.snapshot.activeViews
    val rightViews = rightTracker.snapshot.activeViews

    (
      cancelAfterFirstBatch(joins.stream(semi)) *>
        cancelAfterFirstBatch(joins.stream(anti)) *>
        cancelAfterFirstBatch(unions.stream(union)) *>
        IO:
          assertEquals(leftTracker.snapshot.activeViews, leftViews)
          assertEquals(rightTracker.snapshot.activeViews, rightViews)
          leftTable.close()
          rightTable.close()
          sameSchemaRight.close()
          assertEquals(leftTracker.snapshot.activeOwners, 0)
          assertEquals(rightTracker.snapshot.activeOwners, 0)
    ).unsafeToFuture()

  test("one scoped runtime binds two typed sources and executes their join"):
    type LeftRow = (id: Int, leftLabel: String)
    type RightRow = (key: Int, rightLabel: String)

    val leftRef =
      SourceRef.values("left", "left").fold(error => fail(error.message), identity)
    val rightRef =
      SourceRef.values("right", "right").fold(error => fail(error.message), identity)
    val leftTable = Table
      .fromRows[LeftRow](
        Vector(
          (id = 1, leftLabel = "a"),
          (id = 2, leftLabel = "b")
        )
      )
      .fold(error => fail(error.message), identity)
    val rightTable = Table
      .fromRows[RightRow](
        Vector(
          (key = 2, rightLabel = "matched"),
          (key = 3, rightLabel = "other")
        )
      )
      .fold(error => fail(error.message), identity)
    val leftBinding = SourceBinding[IO, LeftRow, InMemoryFrameSource[IO]](
      leftRef,
      FrameSource.owningResource(
        IO(InMemoryFrameSource[IO](leftTable.schema, leftTable.batches))
      )
    )
    val rightBinding = SourceBinding[IO, RightRow, InMemoryFrameSource[IO]](
      rightRef,
      FrameSource.owningResource(
        IO(InMemoryFrameSource[IO](rightTable.schema, rightTable.batches))
      )
    )
    val query = leftBinding.frame.innerJoin(rightBinding.frame): (left, right) =>
      left.col("id") === right.col("key")

    FrameRuntime
      .resource(leftBinding, rightBinding)
      .flatMap(_.collectWithReceipt(query))
      .use: result =>
        IO:
          assertEquals(result.table.rowCount, 1L)
          assertEquals(
            result.table.row(0),
            Right((id = 2, leftLabel = "b", key = 2, rightLabel = "matched"))
          )
          assertEquals(result.receipt.sources.map(_.reference.id.value), Vector("left", "right"))
          assertEquals(
            result.receipt.residual.map(_._2),
            Vector(PushdownFeature.BatchSize, PushdownFeature.BatchSize)
          )
      .guarantee(IO(leftTable.close()) *> IO(rightTable.close()))
      .unsafeToFuture()

  test("source inspection rejects exact ordered schema mismatch before execution"):
    type Expected = (id: Int, label: String)
    type Actual = (label: String, id: Int)
    val ref =
      SourceRef.values("mismatch", "mismatch").fold(error => fail(error.message), identity)
    val actual = Table
      .fromRows[Actual](Vector((label = "a", id = 1)))
      .fold(error => fail(error.message), identity)
    val binding = SourceBinding[IO, Expected, InMemoryFrameSource[IO]](
      ref,
      FrameSource.owningResource(
        IO(InMemoryFrameSource[IO](actual.schema, actual.batches))
      )
    )

    FrameRuntime
      .resource(binding)
      .use(_ => IO.unit)
      .attempt
      .map:
        case Left(
              RuntimeBindingFailure(
                RuntimeBindingError.SourceSchema(id, expected, observed)
              )
            ) =>
          assertEquals(id.value, "mismatch")
          assertEquals(expected.fields.map(_.name), Vector("id", "label"))
          assertEquals(observed.fields.map(_.name), Vector("label", "id"))
        case other => fail(s"expected exact source schema mismatch, found $other")
      .guarantee(IO(actual.close()))
      .unsafeToFuture()

  test("a pure query and binding can be reacquired and executed more than once"):
    val input = table(new BufferTracker, Vector(Array(1, 2, 3)))
    val binding = SourceBinding[IO, Input, InMemoryFrameSource[IO]](
      reference,
      FrameSource.owningResource(
        IO(InMemoryFrameSource[IO](input.schema, input.batches))
      )
    )
    val query = binding.frame.filter(_.col("id") > Expr.literal(1))

    def execute: IO[Vector[Int]] =
      FrameRuntime
        .resource(binding)
        .flatMap(_.collect(query))
        .use: result =>
          IO.fromEither(
            result.column("id").left.map(TableReadFailure.apply)
          )

    (execute, execute).tupled
      .map: (first, second) =>
        assertEquals(first, Vector(2, 3))
        assertEquals(second, first)
      .guarantee(IO(input.close()))
      .unsafeToFuture()

  test("runtime source finalizers run once on early output termination"):
    Ref
      .of[IO, Int](0)
      .flatMap: closes =>
        val input = table(new BufferTracker, Vector(Array(1), Array(2)))
        val acquire =
          Resource.make(
            IO(InMemoryFrameSource[IO](input.schema, input.batches))
          )(_ => closes.update(_ + 1))
        val binding = SourceBinding[IO, Input, InMemoryFrameSource[IO]](reference, acquire)
        FrameRuntime
          .resource(binding)
          .use(_.stream(binding.frame).take(1).compile.drain)
          .flatMap(_ => closes.get)
          .map(count => assertEquals(count, 1))
          .guarantee(IO(input.close()))
      .unsafeToFuture()
