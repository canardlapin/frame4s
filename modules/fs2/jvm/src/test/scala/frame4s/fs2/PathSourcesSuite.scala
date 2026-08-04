package frame4s.fs2

import cats.effect.IO
import cats.effect.Resource
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import fs2.io.file.Path
import java.io.ByteArrayInputStream
import java.io.InputStream
import frame4s.*

class PathSourcesSuite extends munit.FunSuite:
  type Input = (id: Int, label: String, score: Option[Double])

  private val schema = summon[SchemaDescriptor[Input]].schema

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
          assertEquals(detail, "CSV header does not match the declared schema at column 1")
        case other => fail(s"expected structured path header mismatch, found $other")
      .unsafeToFuture()

  test("JVM CSV and TSV path constructors support single-binding execution"):
    val tsvFile =
      Resource.make(
        IO.blocking:
          val path = java.nio.file.Files.createTempFile("frame4s-e3-", ".tsv")
          java.nio.file.Files.writeString(
            path,
            "id\tlabel\tscore\n1\tAda\t1.5\n2\tLin\t\n"
          )
          Path.fromNioPath(path)
      )((path: Path) => IO.blocking(java.nio.file.Files.deleteIfExists(path.toNioPath)).void)

    (csvFile, tsvFile).tupled
      .use: (csvPath, tsvPath) =>
        val csv = CsvPathSource.binding[IO, Input](csvPath)
        val tsv = TsvPathSource.binding[IO, Input](tsvPath)
        val csvQuery = csv.frame.filter(_.col("id") > 0)
        val tsvQuery = tsv.frame.filter(_.col("id") > 0)
        (csv.render(csvQuery), tsv.render(tsvQuery)).tupled.map: (csvText, tsvText) =>
          assert(csvText.contains("東京"))
          assert(tsvText.contains("Ada"))
          assertEquals(csv.reference.kind, SourceKind.Scan)
          assertEquals(tsv.reference, csv.reference)
      .unsafeToFuture()

  test("missing paths are SourceError.Read failures with retained, non-rendered causes"):
    val missing = Path.fromNioPath(
      java.nio.file.Path.of(
        System.getProperty("java.io.tmpdir"),
        s"frame4s-missing-${java.util.UUID.randomUUID()}-credential=secret.csv"
      )
    )
    val binding = CsvPathSource.binding[IO, Input](reference, missing)

    FrameRuntime
      .resource(binding)
      .flatMap(_.collect(binding.frame))
      .use(_ => IO.unit)
      .attempt
      .map:
        case Left(
              failure @ RuntimeBindingFailure(
                RuntimeBindingError.Source(id, error @ SourceError.Read(cause))
              )
            ) =>
          assertEquals(id, reference.id)
          assert(error.cause.exists(_ eq cause))
          assert(failure.getCause eq cause)
          assert(!failure.getMessage.contains("credential=secret"))
          assert(failure.getMessage.length <= 256)
        case other => fail(s"expected structured path read failure, found $other")
      .unsafeToFuture()

  test("malformed UTF-8 in a path is distinct from path read failure"):
    Resource
      .make(
        IO.blocking:
          val path = java.nio.file.Files.createTempFile("frame4s-invalid-utf8-", ".csv")
          java.nio.file.Files.write(
            path,
            Array[Byte](
              'i'.toByte,
              'd'.toByte,
              ','.toByte,
              'l'.toByte,
              'a'.toByte,
              'b'.toByte,
              'e'.toByte,
              'l'.toByte,
              ','.toByte,
              's'.toByte,
              'c'.toByte,
              'o'.toByte,
              'r'.toByte,
              'e'.toByte,
              '\n'.toByte,
              '1'.toByte,
              ','.toByte,
              0xc3.toByte,
              0x28.toByte,
              ','.toByte,
              '1'.toByte,
              '.'.toByte,
              '0'.toByte,
              '\n'.toByte
            )
          )
          Path.fromNioPath(path)
      )((path: Path) => IO.blocking(java.nio.file.Files.deleteIfExists(path.toNioPath)).void)
      .use: path =>
        val binding = CsvPathSource.binding[IO, Input](reference, path)
        FrameRuntime
          .resource(binding)
          .flatMap(_.collect(binding.frame))
          .use(_ => IO.unit)
          .attempt
      .map:
        case Left(
              failure @ RuntimeBindingFailure(
                RuntimeBindingError.Source(_, error @ SourceError.InvalidUtf8(offset, cause))
              )
            ) =>
          assertEquals(offset, 18L)
          assert(error.cause.exists(_ eq cause))
          assert(failure.getCause eq cause)
        case other => fail(s"expected structured path UTF-8 failure, found $other")
      .unsafeToFuture()

  test("JVM path byte resources distinguish read and close failures"):
    val readCause = new IllegalStateException("read")
    val closeCause = new IllegalStateException("close")
    val readFailure = new InputStream:
      override def read(): Int = throw readCause
    val closeFailure = new ByteArrayInputStream(
      "id,label,score\n1,a,1.0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    ):
      override def close(): Unit = throw closeCause

    def run(input: InputStream): IO[Unit] =
      CsvFrameSource
        .bytes(
          PathByteStream(IO.pure(input)),
          CsvReadOptions(schema)
        )
        .use: source =>
          source
            .plan(ScanRequest())
            .flatMap:
              case Left(error) => IO.raiseError(SourceFailure(error))
              case Right(scan) => scan.batches.compile.drain

    (run(readFailure).attempt, run(closeFailure).attempt).tupled
      .map:
        case (readResult, closeResult) =>
          readResult match
            case Left(failure @ SourceFailure(error @ SourceError.Read(cause))) =>
              assert(cause eq readCause)
              assert(error.cause.exists(_ eq readCause))
              assert(failure.getCause eq readCause)
            case other => fail(s"expected structured path read failure, found $other")
          closeResult match
            case Left(failure @ SourceFailure(error @ SourceError.Close(cause))) =>
              assert(cause eq closeCause)
              assert(error.cause.exists(_ eq closeCause))
              assert(failure.getCause eq closeCause)
            case other => fail(s"expected structured path close failure, found $other")
      .unsafeToFuture()
