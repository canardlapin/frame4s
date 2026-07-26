package frame4s.fs2

import cats.effect.IO
import cats.effect.Resource
import cats.effect.unsafe.implicits.global
import fs2.io.file.Path
import frame4s.*

class PathSourcesSuite extends munit.FunSuite:
  type Input = (id: Int, label: String, score: Option[Double])

  private def reference: SourceRef =
    SourceRef.scan("path-input", "path input").fold(error => fail(error.message), identity)

  private def csvFile: Resource[IO, Path] =
    Resource.make(
      IO.blocking:
        val path = java.nio.file.Files.createTempFile("frame4s-r3-", ".csv")
        java.nio.file.Files.writeString(
          path,
          "id,label,score\n1,東京,1.5\n2,missing,\n"
        )
        Path.fromNioPath(path)
    )((path: Path) => IO.blocking(java.nio.file.Files.deleteIfExists(path.toNioPath)).void)

  test("JVM path binding executes and closes through one Resource scope"):
    csvFile
      .flatMap: path =>
        val binding =
          CsvPathSource.binding[IO, Input](
            reference,
            path,
            CsvSettings(batchSize = 1)
          )
        FrameRuntime
          .resource(binding)
          .flatMap: runtime =>
            val query = binding.frame.filter(_.col("id") > Expr.literal(0))
            runtime.collectWithReceipt(query)
      .use: result =>
        IO.fromEither(
          result.table
            .show(TableRenderOptions(maxRows = 5, maxWidth = 60))
            .left
            .map(TableReadFailure.apply)
        ).map: rendered =>
          assert(rendered.contains("東京"))
          assertEquals(result.table.column("score"), Right(Vector(Some(1.5), None)))
          assertEquals(
            result.receipt.accepted.map(_._2),
            Vector(PushdownFeature.BatchSize)
          )
          assertEquals(
            result.receipt.residual.map(_._2),
            Vector(PushdownFeature.Predicate)
          )
      .unsafeToFuture()

  test("JVM path header mismatch is structured before reference execution"):
    Resource
      .make(
        IO.blocking:
          val path = java.nio.file.Files.createTempFile("frame4s-r3-bad-", ".csv")
          java.nio.file.Files.writeString(path, "label,id,score\nx,1,1.0\n")
          Path.fromNioPath(path)
      )((path: Path) => IO.blocking(java.nio.file.Files.deleteIfExists(path.toNioPath)).void)
      .flatMap: path =>
        val binding = CsvPathSource.binding[IO, Input](reference, path)
        FrameRuntime
          .resource(binding)
          .flatMap(runtime => runtime.collect(binding.frame))
      .use(_ => IO.unit)
      .attempt
      .map:
        case Left(
              RuntimeBindingFailure(
                RuntimeBindingError.Source(
                  _,
                  SourceError.SchemaMismatch(detail)
                )
              )
            ) =>
          assert(detail.contains("label,id,score"))
        case other => fail(s"expected structured path header mismatch, found $other")
      .unsafeToFuture()
