package frame4s.fs2

import cats.effect.IO
import cats.effect.Ref
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.Stream
import frame4s.*
import scala.concurrent.duration.*

class DelimitedRoundTripSuite extends munit.FunSuite:
  type TextRow = (value: Option[String])
  type Input = (id: Int, label: String, score: Option[Double])

  private val textSchema = summon[SchemaDescriptor[TextRow]].schema
  private val inputSchema = summon[SchemaDescriptor[Input]].schema

  private def policy(
      tokens: Set[String],
      writeToken: String
  ): NullPolicy =
    NullPolicy
      .unquotedTokens(tokens, writeToken)
      .fold(error => fail(error.message), identity)

  private def limits(
      record: Int,
      field: Int,
      excerpt: Int
  ): DelimitedReadLimits =
    DelimitedReadLimits
      .create(record, field, excerpt)
      .fold(error => fail(error.message), identity)

  private def sourceValues(
      source: cats.effect.Resource[IO, ? <: FrameSource[IO]]
  ): IO[Vector[Option[String]]] =
    source.use: acquired =>
      acquired
        .plan(ScanRequest())
        .flatMap:
          case Left(error) => IO.raiseError(SourceFailure(error))
          case Right(scan) =>
            scan.batches
              .evalMap: batch =>
                IO.fromEither(
                  batch
                    .column("value")
                    .leftMap(error => SourceFailure(SourceError.Storage(error)))
                ).flatMap: column =>
                  (0 until batch.rowCount).toVector.traverse: row =>
                    IO.fromEither(
                      column
                        .scalar(row)
                        .leftMap(error => SourceFailure(SourceError.Storage(error)))
                    ).map:
                      case ScalarValue.Null         => None
                      case ScalarValue.Utf8(actual) => Some(actual.value)
                      case other                    => fail(s"expected UTF-8 scalar, found $other")
              .compile
              .toVector
              .map(_.flatten)

  private def table(rows: Vector[TextRow]): Table[TextRow] =
    Table.fromRows(rows, batchSize = 2).fold(error => fail(error.message), identity)

  test("CSV round trips null, empty text, every null token, BOM text, and Unicode distinctly"):
    val nulls = policy(Set("", "null", "NULL"), writeToken = "NULL")
    val rows = Vector[TextRow](
      (value = None),
      (value = Some("")),
      (value = Some("null")),
      (value = Some("NULL")),
      (value = Some("x")),
      (value = Some(" padded ")),
      (value = Some("\ufeffreal")),
      (value = Some("東京😀"))
    )
    val input = table(rows)
    val sink = new CsvFrameSink[IO](nullPolicy = nulls)

    sink
      .write(textSchema, Stream.emits(input.batches).covary[IO])
      .guarantee(IO(input.close()))
      .flatMap:
        case Left(error)    => IO(fail(error.message))
        case Right(written) =>
          IO:
            assert(written.text.contains("\nNULL\n"))
            assert(written.text.contains("\n\"\"\n"))
            assert(written.text.contains("\n\"null\"\n"))
            assert(written.text.contains("\n\"NULL\"\n"))
            assert(written.text.contains("\n\"\ufeffreal\"\n"))
            assert(written.text.contains("\n\" padded \"\n"))
            assertEquals(written.receipt.bytes, 62L)
          *> sourceValues(
            CsvFrameSource.resource(
              written.text,
              CsvReadOptions(
                textSchema,
                nullPolicy = nulls,
                coercion = CsvCoercion.TrimWhitespace,
                batchSize = 2
              )
            )
          ).map(observed => assertEquals(observed, rows.map(_.value)))
      .unsafeToFuture()

  test("quoted null tokens are data, unquoted tokens are null, and a blank record is one field"):
    val nulls = policy(Set("", "null", "NULL"), writeToken = "")
    val text = "value\n\n\"\"\nnull\n\"null\"\nNULL\n\"NULL\"\n"

    sourceValues(
      CsvFrameSource.resource(
        text,
        CsvReadOptions(textSchema, nullPolicy = nulls, batchSize = 1)
      )
    ).map: observed =>
      assertEquals(
        observed,
        Vector(None, Some(""), None, Some("null"), None, Some("NULL"))
      )
    .unsafeToFuture()

  test("TSV uses the same quote-aware null policy and UTF-8 byte receipt"):
    val nulls = policy(Set("", "missing"), writeToken = "missing")
    val rows = Vector[TextRow](
      (value = None),
      (value = Some("")),
      (value = Some("missing")),
      (value = Some("a\tb")),
      (value = Some("λ"))
    )
    val input = table(rows)

    new TsvFrameSink[IO](nullPolicy = nulls)
      .write(textSchema, Stream.emits(input.batches).covary[IO])
      .guarantee(IO(input.close()))
      .flatMap:
        case Left(error)    => IO(fail(error.message))
        case Right(written) =>
          IO(assertEquals(written.receipt.bytes, 36L)) *>
            sourceValues(
              TsvFrameSource.resource(
                written.text,
                TsvReadOptions(textSchema, nullPolicy = nulls, batchSize = 1)
              )
            ).map(observed => assertEquals(observed, rows.map(_.value)))
      .unsafeToFuture()

  test("a transport BOM is ignored without consuming a quoted BOM data value"):
    val text = "\ufeffvalue\n\"\ufeffdata\"\n"
    sourceValues(
      CsvFrameSource.resource(
        text,
        CsvReadOptions(textSchema)
      )
    ).map(observed => assertEquals(observed, Vector(Some("\ufeffdata"))))
      .unsafeToFuture()

  test("null policy construction and unsafe sink tokens fail before corrupting output"):
    assertEquals(
      NullPolicy.unquotedTokens(Set.empty, ""),
      Left(NullPolicyError.EmptyTokens)
    )
    assertEquals(
      NullPolicy.unquotedTokens(Set("null"), ""),
      Left(NullPolicyError.WriteTokenNotRecognized(""))
    )
    assertEquals(
      NullPolicy.unquotedTokens(Set("", null), ""),
      Left(NullPolicyError.NullToken)
    )
    assertEquals(
      NullPolicy.unquotedTokens(null, ""),
      Left(NullPolicyError.NullToken)
    )

    val input = table(Vector((value = None)))
    val unsafe = policy(Set("a,b"), writeToken = "a,b")
    val unsafeResult =
      new CsvFrameSink[IO](nullPolicy = unsafe)
        .write(textSchema, Stream.emits(input.batches).covary[IO])
        .guarantee(IO(input.close()))
    val whitespaceResult =
      new CsvFrameSink[IO](nullPolicy = policy(Set(" NA "), " NA "))
        .write(textSchema, Stream.empty)

    (unsafeResult, whitespaceResult).tupled
      .map: results =>
        results.toList.foreach:
          case Left(SinkError.InvalidRequest(detail)) =>
            assert(detail.contains("null write token"))
          case other => fail(s"expected invalid sink null token, found $other")
      .unsafeToFuture()

  test("raw-null delimited policies return structured request errors"):
    val sinkResult =
      new CsvFrameSink[IO](nullPolicy = null)
        .write(textSchema, Stream.empty)
    val sourceResult =
      CsvFrameSource
        .resource[IO](
          "value\nx\n",
          CsvReadOptions(textSchema, nullPolicy = null)
        )
        .use(_.inspect)

    (sinkResult, sourceResult).tupled
      .map: (sink, source) =>
        assertEquals(
          sink,
          Left(SinkError.InvalidRequest("CSV null policy cannot be raw null"))
        )
        assertEquals(
          source,
          Left(SourceError.InvalidRequest("CSV null policy cannot be raw null"))
        )
      .unsafeToFuture()

  test("field and record limits are exact across arbitrary character chunks"):
    def outcome(
        text: String,
        configured: DelimitedReadLimits,
        chunkSize: Int
    ): IO[Either[Throwable, Unit]] =
      val input =
        Stream
          .emits(text.toVector)
          .covary[IO]
          .chunkN(chunkSize)
          .flatMap(Stream.chunk)
      CsvFrameSource
        .characters(
          input,
          CsvReadOptions(inputSchema, header = false, limits = configured)
        )
        .use: source =>
          source
            .plan(ScanRequest())
            .flatMap:
              case Left(error) => IO.raiseError(SourceFailure(error))
              case Right(scan) => scan.batches.compile.drain
        .attempt

    val fieldLimits = limits(record = 32, field = 4, excerpt = 3)
    val recordLimits = limits(record = 5, field = 5, excerpt = 3)

    (1 to 5).toVector
      .traverse: chunkSize =>
        (
          outcome("1,abcd,1", fieldLimits, chunkSize),
          outcome("1,abcde,1", fieldLimits, chunkSize),
          outcome("1,a,2", recordLimits, chunkSize),
          outcome("1,a,20", recordLimits, chunkSize)
        ).tupled
      .map:
        _.foreach: (fieldExact, fieldOver, recordExact, recordOver) =>
          assertEquals(fieldExact, Right(()))
          fieldOver match
            case Left(
                  SourceFailure(
                    SourceError.MalformedDelimited(location, detail, excerpt)
                  )
                ) =>
              assertEquals(location, SourceLocation(1L, 2, 6L))
              assert(detail.contains("field exceeds 4 characters"))
              assertEquals(excerpt.text, "cde")
              assertEquals(excerpt.startOffset, 4L)
              assert(excerpt.truncatedBefore)
            case other => fail(s"expected field limit failure, found $other")
          assertEquals(recordExact, Right(()))
          recordOver match
            case Left(
                  SourceFailure(
                    SourceError.MalformedDelimited(location, detail, excerpt)
                  )
                ) =>
              assertEquals(location, SourceLocation(1L, 3, 5L))
              assert(detail.contains("record exceeds 5 characters"))
              assertEquals(excerpt.text, "a,2")
              assertEquals(excerpt.startOffset, 2L)
              assert(excerpt.truncatedBefore)
            case other => fail(s"expected record limit failure, found $other")
      .unsafeToFuture()

  test("excess schema width fails at its delimiter without awaiting the source tail"):
    Ref
      .of[IO, Int](0)
      .flatMap: finalized =>
        val prefix = "id,label,score\n1,a,1.0,"
        val input =
          (Stream.emits(prefix.toVector).covary[IO] ++ Stream.never[IO])
            .onFinalize(finalized.update(_ + 1))
        CsvFrameSource
          .characters(input, CsvReadOptions(inputSchema))
          .use: source =>
            source
              .plan(ScanRequest())
              .flatMap:
                case Left(error) => IO.raiseError(SourceFailure(error))
                case Right(scan) => scan.batches.compile.drain
          .attempt
          .timeout(2.seconds)
          .flatMap(result => finalized.get.tupleLeft(result))
          .map: (result, count) =>
            result match
              case Left(
                    SourceFailure(
                      SourceError.MalformedDelimited(location, detail, excerpt)
                    )
                  ) =>
                assertEquals(location, SourceLocation(2L, 4, 22L))
                assert(detail.contains("expected 3 fields but found more"))
                assertEquals(excerpt.text, "1,a,1.0,")
              case other => fail(s"expected prompt schema-width failure, found $other")
            assertEquals(count, 1)
      .unsafeToFuture()

  test("limit policies reject invalid construction and bound retained diagnostics"):
    assertEquals(
      DelimitedReadLimits.create(2, 3, 1),
      Left(DelimitedLimitError.FieldExceedsRecord(field = 3, record = 2))
    )
    val configured = limits(record = 32, field = 16, excerpt = 3)
    CsvFrameSource
      .resource[IO](
        "\"xxxxxx\",a,1.0\n",
        CsvReadOptions(inputSchema, header = false, limits = configured)
      )
      .use: source =>
        source
          .plan(ScanRequest())
          .flatMap:
            case Left(error) => IO.raiseError(SourceFailure(error))
            case Right(scan) => scan.batches.compile.drain
      .attempt
      .map:
        case Left(
              SourceFailure(
                SourceError.Decode(
                  SourceLocation(1L, 1, 0L),
                  excerpt,
                  DataType.Int32
                )
              )
            ) =>
          assertEquals(excerpt.text, "\"xx")
          assertEquals(excerpt.startOffset, 0L)
          assert(excerpt.truncatedAfter)
          assert(
            !SourceError
              .Decode(SourceLocation(1, 1, 0), excerpt, DataType.Int32)
              .message
              .contains("\"xx")
          )
        case other => fail(s"expected bounded decode failure, found $other")
      .unsafeToFuture()
