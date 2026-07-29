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

def selected(frame: Frame[CsvObservation]): Frame[CsvSelected] =
  frame
    .filter(row => row.col("id") > 0)
    .select(row => (row.col("label"), row.col("score")))

val csvSource = CsvFrameSource.binding[IO, CsvObservation](
  sampleCsv,
  CsvSettings(batchSize = 256)
)

def csvProgram: IO[String] = csvSource.render(selected(csvSource.frame))
```

```scala mdoc
csvProgram.unsafeRunSync()
```

On the JVM, use the same schema and query with a filesystem path:

```scala mdoc:compile-only
import _root_.fs2.io.file.Path

def csvFileProgram(path: Path): IO[String] =
  val source = CsvPathSource.binding[IO, CsvObservation](
    path,
    CsvSettings(batchSize = 256)
  )
  source.render(selected(source.frame))
```

`CsvFrameSource` incrementally parses strings, byte streams, or character
streams on both supported platforms. `CsvPathSource` owns the opened JVM file
inside the runtime resource. Use `TsvFrameSource`, `TsvPathSource`, and
`TsvSettings` for tab-delimited input. These constructors are for a source used
alone. A join supplies an explicit `SourceRef` for each input, as described in
[execution and ownership](execution-and-ownership.md).

Next, learn how [schemas, nullability, and column errors](schemas-and-errors.md)
are represented.
