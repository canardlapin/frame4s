package frame4s.fs2

import cats.effect.{IO, Ref, Deferred}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.{Chunk, Stream}

class DelimitedHeaderSuite extends munit.FunSuite:
  private def characters(text: String): Stream[IO, Char] = Stream.emits(text.toVector).covary[IO]
  private def names(result: Either[SourceError, DelimitedHeader]): Vector[String] =
    result.fold(error => fail(error.message), _.columnNames)

  test("CSV header preserves names across every character partition and EOF"):
    val text =
      "\ufeffparticipant,\"score, raw\",\"multi\nline\",\"a\"\"b\", padded \r\n\"invalid later row"
    val expected = Vector("participant", "score, raw", "multi\nline", "a\"b", " padded ")
    (0 to text.length).toVector
      .traverse_ { split =>
        val stream = characters(text.take(split)) ++ characters(text.drop(split))
        DelimitedHeader.characters(stream).map(result => assertEquals(names(result), expected))
      }
      .flatMap(_ => DelimitedHeader.characters(characters("one,two")))
      .map(result => assertEquals(names(result), Vector("one", "two")))
      .unsafeToFuture()

  test("strict UTF-8 header handles split multibyte text but does not decode a malformed tail"):
    val prefix = "id,東京😀\n".getBytes("UTF-8").toVector
    val bytes = prefix ++ Vector(0xc3.toByte, 0x28.toByte)
    (1 to bytes.size).toVector
      .traverse_ { size =>
        val input = Stream.emits(bytes).covary[IO].chunkN(size).unchunks
        DelimitedHeader
          .bytes(input)
          .map(result => assertEquals(names(result), Vector("id", "東京😀")))
      }
      .flatMap(_ =>
        DelimitedHeader.bytes(Stream.chunk(Chunk.array(Array(0xc3.toByte, 0x28.toByte))).covary[IO])
      )
      .map(result =>
        assert(result.left.exists { case SourceError.InvalidUtf8(1L, _) => true; case _ => false })
      )
      .unsafeToFuture()

  test("TSV has explicit delimiter and quoted tabs remain header data"):
    DelimitedHeader
      .characters(
        characters("participant\t\"reaction\ttime\"\n"),
        DelimitedHeaderOptions(delimiter = '\t')
      )
      .map(result => assertEquals(names(result), Vector("participant", "reaction\ttime")))
      .unsafeToFuture()

  test("empty duplicate blank and malformed headers fail with structured locations"):
    Vector("", "\n", "a,a\n", "a,   \n", "a,\"unterminated", "a,b\"c\n")
      .traverse_ { text =>
        DelimitedHeader
          .characters(characters(text))
          .map(result =>
            assert(result.left.exists {
              case SourceError.MalformedDelimited(location, _, _) => location.record == 1L
              case _                                              => false
            })
          )
      }
      .unsafeToFuture()

  test("column field and record bounds fail at the header without reading an unbounded tail"):
    val limits = DelimitedReadLimits
      .create(maxRecordChars = 5, maxFieldChars = 3, maxErrorExcerptChars = 2)
      .toOption
      .get
    val cases = Vector(
      ("a,b,c", DelimitedHeaderOptions(maxColumns = 2)),
      ("long", DelimitedHeaderOptions(limits = limits)),
      ("abc,ab", DelimitedHeaderOptions(limits = limits))
    )
    cases
      .traverse_ { (text, options) =>
        DelimitedHeader
          .characters(characters(text) ++ Stream.never[IO], options)
          .map(result => assert(result.isLeft))
      }
      .flatMap(_ =>
        DelimitedHeader.characters(
          characters("abc,d\n"),
          DelimitedHeaderOptions(maxColumns = 2, limits = limits)
        )
      )
      .map(result => assertEquals(names(result), Vector("abc", "d")))
      .unsafeToFuture()

  test("invalid options do not acquire the source"):
    Ref
      .of[IO, Int](0)
      .flatMap { acquired =>
        val input = Stream.eval(acquired.update(_ + 1)).drain ++ characters("a\n")
        Vector(DelimitedHeaderOptions(maxColumns = 0), DelimitedHeaderOptions(delimiter = '"'))
          .traverse_ { options =>
            DelimitedHeader
              .characters(input, options)
              .map(result =>
                assert(result.left.exists {
                  case SourceError.InvalidRequest(_) => true
                  case _                             => false
                })
              )
          } *> acquired.get.map(value => assertEquals(value, 0))
      }
      .unsafeToFuture()

  test("success and failure finalize once and do not evaluate the source after the header"):
    Vector("a\n", "a,a\n")
      .traverse_ { text =>
        Ref.of[IO, Int](0).flatMap { closed =>
          val input =
            (characters(text) ++ Stream.raiseError[IO](new IllegalStateException("tail evaluated")))
              .onFinalize(closed.update(_ + 1))
          DelimitedHeader.characters(input).flatMap { result =>
            IO(assertEquals(result.isRight, text == "a\n")) *> closed.get
              .map(value => assertEquals(value, 1))
          }
        }
      }
      .unsafeToFuture()

  test("cancellation retains stream finalization and upstream failures remain structured"):
    (for
      closed <- Ref.of[IO, Int](0)
      started <- Deferred[IO, Unit]
      input = (Stream.eval(started.complete(())).drain ++ characters("\"partial") ++ Stream
        .never[IO]).onFinalize(closed.update(_ + 1))
      fiber <- DelimitedHeader.characters(input).start
      _ <- started.get
      _ <- fiber.cancel
      _ <- fiber.join
      count <- closed.get
      _ <- IO(assertEquals(count, 1))
      failed <- DelimitedHeader.characters(
        Stream.raiseError[IO](new IllegalStateException("upstream"))
      )
      _ <- IO(assert(failed.left.exists { case SourceError.Upstream(_) => true; case _ => false }))
    yield ()).unsafeToFuture()

  test("character and byte failures retain structured causes and finalize once"):
    Vector(false, true)
      .traverse_ { useBytes =>
        Vector(false, true).traverse_ { structured =>
          Ref.of[IO, Int](0).flatMap { closed =>
            val cause = new IllegalStateException("upstream")
            val expected = SourceError.Upstream(cause)
            val failure: Throwable = if structured then SourceFailure(expected) else cause
            val result =
              if useBytes then
                DelimitedHeader.bytes(
                  Stream.raiseError[IO](failure).onFinalize(closed.update(_ + 1))
                )
              else
                DelimitedHeader.characters(
                  Stream.raiseError[IO](failure).onFinalize(closed.update(_ + 1))
                )
            result.flatMap { outcome =>
              IO(assertEquals(outcome.left.toOption, Some(expected))) *>
                closed.get.map(value => assertEquals(value, 1))
            }
          }
        }
      }
      .unsafeToFuture()
