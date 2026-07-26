package frame4s.fs2

import cats.effect.IO
import cats.effect.Ref
import cats.effect.Deferred
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import frame4s.*

class FrameIOSuite extends munit.FunSuite:
  type Input = (id: Int, label: String, score: Option[Double])

  private val schema = summon[SchemaDescriptor[Input]].schema

  private def storage[A](result: Either[StorageError, A]): A =
    result.fold(error => fail(error.message), identity)

  private def batch(tracker: BufferTracker): RecordBatch =
    storage:
      RecordBatch(
        schema,
        Vector(
          storage(ColumnArray.int32(Array(1, 2, 3), tracker = tracker)),
          storage(ColumnArray.utf8(Array("a", "b", "c"), tracker = tracker)),
          storage(
            ColumnArray.float64(
              Array(1.5, 0.0, Double.NaN),
              Array(true, false, true),
              tracker
            )
          )
        )
      )

  private def scalar(batch: RecordBatch, name: String): Vector[ScalarValue] =
    val column = storage(batch.column(name))
    Vector.tabulate(batch.rowCount)(index => storage(column.scalar(index)))

  test("in-memory scans accept supported pushdown and preserve unsupported residuals"):
    val tracker = new BufferTracker
    val input = batch(tracker)
    val source = InMemoryFrameSource[IO](schema, Vector(input))
    val request = ScanRequest(
      columns = Vector("label"),
      predicate = Some(PortablePredicate.IsNull("score")),
      limit = Some(2),
      batchSize = Some(1)
    )

    source
      .plan(request)
      .flatMap:
        case Left(error) => IO(fail(error.message))
        case Right(scan) =>
          scan.batches
            .evalMap(batch => IO(scalar(batch, "label")))
            .compile
            .toVector
            .map(_.flatten)
            .flatMap: labels =>
              IO:
                assertEquals(scan.schema.fields.map(_.name), Vector("label"))
                assertEquals(
                  scan.receipt.accepted,
                  Vector(PushdownFeature.Projection, PushdownFeature.Limit)
                )
                assertEquals(
                  scan.receipt.residual,
                  Vector(PushdownFeature.Predicate, PushdownFeature.BatchSize)
                )
                assertEquals(labels, Vector("a", "b").map(ScalarValue.Utf8.apply))
      .flatMap: _ =>
        IO:
          assertEquals(tracker.snapshot.activeOwners, 5)
          assertEquals(tracker.snapshot.activeViews, 5)
          input.close()
          assertEquals(tracker.snapshot.activeOwners, 0)
      .unsafeToFuture()

  test("duplicate projected source names return a structured planning error"):
    val tracker = new BufferTracker
    val input = batch(tracker)
    val source = InMemoryFrameSource[IO](schema, Vector(input))

    source
      .plan(ScanRequest(columns = Vector("id", "id")))
      .map: result =>
        assertEquals(
          result,
          Left(SourceError.InvalidRequest("field 'id' occurs more than once"))
        )
        input.close()
      .unsafeToFuture()

  test("CSV ingestion and writing preserve explicit schema, nulls, quotes, and NaN"):
    val input =
      """id,label,score
        |1,"a,b",1.5
        |2,missing,
        |3,nan,NaN
        |""".stripMargin
    val options = CsvReadOptions(schema, batchSize = 2)
    val sink = new CsvFrameSink[IO]()
    var acquired: Option[CsvFrameSource[IO]] = None

    CsvFrameSource
      .resource[IO](input, options)
      .use: source =>
        acquired = Some(source)
        source
          .plan(ScanRequest())
          .flatMap:
            case Left(error) => IO(fail(error.message))
            case Right(scan) =>
              scan.batches
                .evalMap: batch =>
                  IO((batch.rowCount, scalar(batch, "label"), scalar(batch, "score")))
                .compile
                .toVector
                .flatMap: observed =>
                  IO:
                    assertEquals(observed.map(_._1), Vector(2, 1))
                    assertEquals(
                      observed.flatMap(_._2),
                      Vector("a,b", "missing", "nan").map(ScalarValue.Utf8.apply)
                    )
                    assertEquals(
                      observed.flatMap(_._3).take(2),
                      Vector(ScalarValue.Float64(1.5), ScalarValue.Null)
                    )
                  *> source
                    .plan(ScanRequest())
                    .flatMap:
                      case Left(error)      => IO(fail(error.message))
                      case Right(writeScan) =>
                        sink
                          .write(schema, writeScan.batches)
                          .flatMap:
                            case Left(error)   => IO(fail(error.message))
                            case Right(result) =>
                              IO:
                                assertEquals(result.receipt.rows, 3L)
                                assert(result.text.contains("\"a,b\""))
                                assert(result.text.contains("3,nan,NaN"))
      .flatMap: _ =>
        acquired.get.inspect.map:
          case Left(SourceError.Open(_)) => ()
          case other                     => fail(s"expected finalized CSV source, found $other")
      .unsafeToFuture()

  test("CSV decoding failures are structured and row-addressed"):
    CsvFrameSource
      .resource[IO](
        "id,label,score\nnot-an-int,a,1.0\n",
        CsvReadOptions(schema)
      )
      .use: source =>
        source
          .plan(ScanRequest())
          .flatMap:
            case Left(error) => IO.raiseError(SourceFailure(error))
            case Right(scan) => scan.batches.compile.drain
      .attempt
      .map:
        case Left(SourceFailure(SourceError.Decode(2, 1, "not-an-int", DataType.Int32))) =>
          ()
        case other => fail(s"expected structured CSV decode error, found $other")
      .unsafeToFuture()

  test(
    "incremental CSV handles hostile byte splits, quoted newlines, escaped quotes, CRLF, and terminal nulls"
  ):
    val text =
      "id,label,score\r\n" +
        "1,\"東\n京\",1.0\r\n" +
        "2,\"a\"\"b\",NULL\r\n" +
        "3,empty,\r\n"
    val oneByteChunks =
      Stream
        .emits(text.getBytes("UTF-8").toVector)
        .covary[IO]
        .chunkN(1)
        .flatMap(Stream.chunk)

    CsvFrameSource
      .bytes[IO](
        oneByteChunks,
        CsvReadOptions(schema, nullTokens = Set("", "NULL"), batchSize = 2)
      )
      .use: source =>
        source
          .plan(ScanRequest())
          .flatMap:
            case Left(error) => IO(fail(error.message))
            case Right(scan) =>
              scan.batches
                .evalMap: batch =>
                  IO((scalar(batch, "label"), scalar(batch, "score")))
                .compile
                .toVector
                .map: observed =>
                  assertEquals(
                    observed.flatMap(_._1),
                    Vector("東\n京", "a\"b", "empty").map(ScalarValue.Utf8.apply)
                  )
                  assertEquals(
                    observed.flatMap(_._2),
                    Vector(
                      ScalarValue.Float64(1.0),
                      ScalarValue.Null,
                      ScalarValue.Null
                    )
                  )
      .unsafeToFuture()

  test("header and malformed-record failures occur in the stream with logical row details"):
    val wrongHeader =
      CsvFrameSource
        .resource[IO]("label,id,score\nx,1,1.0\n", CsvReadOptions(schema))
        .use: source =>
          source
            .plan(ScanRequest())
            .flatMap:
              case Left(error) => IO.raiseError(SourceFailure(error))
              case Right(scan) => scan.batches.compile.drain
        .attempt
    val malformed =
      CsvFrameSource
        .resource[IO]("id,label,score\n1,a\n", CsvReadOptions(schema))
        .use: source =>
          source
            .plan(ScanRequest())
            .flatMap:
              case Left(error) => IO.raiseError(SourceFailure(error))
              case Right(scan) => scan.batches.compile.drain
        .attempt

    (wrongHeader, malformed).tupled
      .map: (headerResult, malformedResult) =>
        headerResult match
          case Left(SourceFailure(SourceError.SchemaMismatch(detail))) =>
            assert(detail.contains("label,id,score"))
            assert(detail.contains("id,label,score"))
          case other => fail(s"expected exact header mismatch, found $other")
        malformedResult match
          case Left(SourceFailure(SourceError.MalformedCsv(2, detail))) =>
            assert(detail.contains("expected 3 fields but found 2"))
          case other => fail(s"expected logical row 2 malformed error, found $other")
      .unsafeToFuture()

  test("early termination finalizes the input and every emitted batch exactly once"):
    Ref
      .of[IO, Int](0)
      .flatMap: finalized =>
        var emitted: Option[RecordBatch] = None
        val input =
          (Stream.emits("id,label,score\n1,a,1.0\n2,b,2.0\n".toVector).covary[IO] ++
            Stream.never[IO])
            .onFinalize(finalized.update(_ + 1))
        CsvFrameSource
          .characters[IO](input, CsvReadOptions(schema, batchSize = 1))
          .use: source =>
            source
              .plan(ScanRequest())
              .flatMap:
                case Left(error) => IO(fail(error.message))
                case Right(scan) =>
                  scan.batches
                    .evalTap(batch => IO { emitted = Some(batch) })
                    .take(1)
                    .compile
                    .drain
          .flatMap(_ => (finalized.get, IO(emitted)).tupled)
          .map: (count, batch) =>
            assertEquals(count, 1)
            batch match
              case Some(value) =>
                assertEquals(value.column("id"), Left(StorageError.BufferClosed))
              case None => fail("expected one emitted batch")
      .unsafeToFuture()

  test("cancellation finalizes the input and in-flight decoded batch exactly once"):
    (Ref.of[IO, Int](0), Deferred[IO, Unit]).tupled
      .flatMap: (finalized, started) =>
        var emitted: Option[RecordBatch] = None
        val input =
          (Stream.emits("id,label,score\n1,a,1.0\n".toVector).covary[IO] ++
            Stream.never[IO])
            .onFinalize(finalized.update(_ + 1))
        CsvFrameSource
          .characters[IO](input, CsvReadOptions(schema, batchSize = 1))
          .use: source =>
            source
              .plan(ScanRequest())
              .flatMap:
                case Left(error) => IO(fail(error.message))
                case Right(scan) =>
                  for
                    fiber <- scan.batches
                      .evalMap: batch =>
                        IO { emitted = Some(batch) } *>
                          started.complete(()).void *>
                          IO.never
                      .compile
                      .drain
                      .start
                    _ <- started.get
                    _ <- fiber.cancel
                  yield ()
          .flatMap(_ => (finalized.get, IO(emitted)).tupled)
          .map: (count, batch) =>
            assertEquals(count, 1)
            batch match
              case Some(value) =>
                assertEquals(value.column("id"), Left(StorageError.BufferClosed))
              case None => fail("expected one in-flight batch")
      .unsafeToFuture()

  test("streaming batch retention is bounded by the configured batch size"):
    val input =
      Stream.emits("id,label,score\n".toVector).covary[IO] ++
        Stream
          .iterate(0)(_ + 1)
          .take(10000)
          .flatMap: index =>
            Stream.emits(s"$index,value-$index,${index.toDouble}\n".toVector)
    CsvFrameSource
      .characters[IO](input, CsvReadOptions(schema, batchSize = 17))
      .use: source =>
        source
          .plan(ScanRequest())
          .flatMap:
            case Left(error) => IO(fail(error.message))
            case Right(scan) =>
              scan.batches
                .map(_.rowCount)
                .compile
                .toVector
                .map: sizes =>
                  assertEquals(sizes.sum, 10000)
                  assertEquals(sizes.max, 17)
                  assertEquals(sizes.last, 4)
      .unsafeToFuture()

  test("TSV convenience source and sink round-trip tabs, quotes, and nulls"):
    val input =
      "id\tlabel\tscore\n" +
        "1\t\"a\tb\"\t1.5\n" +
        "2\tmissing\t\n" +
        "3\tnan\tNaN\n"
    val sink = new TsvFrameSink[IO]()

    TsvFrameSource
      .resource[IO](input, TsvReadOptions(schema, batchSize = 2))
      .use: source =>
        source
          .plan(ScanRequest())
          .flatMap:
            case Left(error) => IO(fail(error.message))
            case Right(scan) =>
              scan.batches
                .evalMap: batch =>
                  IO((scalar(batch, "label"), scalar(batch, "score")))
                .compile
                .toVector
                .flatMap: observed =>
                  IO:
                    assertEquals(
                      observed.flatMap(_._1),
                      Vector("a\tb", "missing", "nan").map(ScalarValue.Utf8.apply)
                    )
                    assertEquals(
                      observed.flatMap(_._2).take(2),
                      Vector(ScalarValue.Float64(1.5), ScalarValue.Null)
                    )
              *> source
                .plan(ScanRequest())
                .flatMap:
                  case Left(error)      => IO(fail(error.message))
                  case Right(writeScan) =>
                    sink
                      .write(schema, writeScan.batches)
                      .flatMap:
                        case Left(error)   => IO(fail(error.message))
                        case Right(result) =>
                          IO:
                            assertEquals(result.receipt.rows, 3L)
                            assert(result.text.startsWith("id\tlabel\tscore\n"))
                            assert(result.text.contains("1\t\"a\tb\"\t1.5"))
                            assert(result.text.contains("2\tmissing\t\n"))
      .unsafeToFuture()
