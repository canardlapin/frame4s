package frame4s.fs2

import cats.effect.{IO, Resource}
import cats.effect.unsafe.implicits.global
import fs2.io.file.Path

class DelimitedHeaderPathSuite extends munit.FunSuite:
  test("public JVM path inspection reads a quoted TSV header and releases the file"):
    Resource
      .make(IO.blocking(java.nio.file.Files.createTempFile("frame4s-header-", ".tsv")))(p =>
        IO.blocking(java.nio.file.Files.deleteIfExists(p)).void
      )
      .use { path =>
        IO.blocking(
          java.nio.file.Files.writeString(path, "participant\t\"score\tname\"\n\"malformed tail")
        ) *>
          DelimitedHeaderPath
            .read[IO](Path.fromNioPath(path), DelimitedHeaderOptions(delimiter = '\t'))
            .flatMap { result =>
              IO(
                assertEquals(
                  result.toOption.map(_.columnNames),
                  Some(Vector("participant", "score\tname"))
                )
              ) *>
                IO.blocking(java.nio.file.Files.delete(path))
            }
      }
      .unsafeToFuture()

  test("missing paths return the structured read failure"):
    Resource
      .make(IO.blocking(java.nio.file.Files.createTempDirectory("frame4s-header-missing-")))(p =>
        IO.blocking(java.nio.file.Files.delete(p))
      )
      .use { directory =>
        DelimitedHeaderPath.read[IO](Path.fromNioPath(directory.resolve("absent.csv"))).map {
          result =>
            assert(result.left.exists { case SourceError.Read(_) => true; case _ => false })
        }
      }
      .unsafeToFuture()
