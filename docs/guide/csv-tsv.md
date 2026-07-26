# Read typed CSV and TSV data

CSV ingestion needs an explicit named-tuple schema. The parser checks the file
header and each decoded value against that schema; it does not turn runtime
inference into a compile-time claim.

This example uses an in-memory CSV string so the documentation build can run it
on both the JVM and Scala.js:

```scala mdoc:silent
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import frame4s.*
import frame4s.fs2.*

type CsvObservation = (id: Int, label: String, score: Option[Double])
type CsvSelected = (label: String, score: Option[Double])

val sampleCsv =
  """id,label,score
    |1,alpha,4.0
    |2,東京,
    |""".stripMargin

def checkedCsv[A](result: Either[FrameError, A]): IO[A] =
  IO.fromEither(
    result.left.map(error => new IllegalArgumentException(error.message))
  )

def csvProgram: IO[String] =
  checkedCsv(SourceRef.scan("observations", "sample.csv")).flatMap: reference =>
    val source = CsvFrameSource.binding[IO, CsvObservation](
      reference,
      sampleCsv,
      CsvSettings(batchSize = 256)
    )
    val query: Frame[CsvSelected] = source.frame
      .filter(row => row.col("id") > Expr.literal(0))
      .select(row =>
        (
          row.col("label").as("label"),
          row.col("score").as("score")
        )
      )

    FrameRuntime
      .resource(source)
      .flatMap(_.collect(query))
      .use(table =>
        IO.fromEither(table.show().left.map(TableReadFailure.apply))
      )
```

```scala mdoc
csvProgram.unsafeRunSync()
```

On the JVM, use the same schema and query with a filesystem path:

```scala mdoc:compile-only
import _root_.fs2.io.file.Path

def csvFileProgram(path: Path): IO[String] =
  checkedCsv(SourceRef.scan("observations", path.toString)).flatMap: reference =>
    val source = CsvPathSource.binding[IO, CsvObservation](
      reference,
      path,
      CsvSettings(batchSize = 256)
    )
    FrameRuntime
      .resource(source)
      .flatMap(_.collect(source.frame))
      .use(table =>
        IO.fromEither(table.show().left.map(TableReadFailure.apply))
      )
```

`CsvFrameSource` incrementally parses strings, byte streams, or character
streams on both supported platforms. `CsvPathSource` owns the opened JVM file
inside the runtime resource. Use `TsvFrameSource`, `TsvPathSource`, and
`TsvSettings` for tab-delimited input.

Next, learn how [schemas, nullability, and column errors](schemas-and-errors.md)
are represented.
