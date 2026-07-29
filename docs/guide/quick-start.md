# Install frame4s and print a typed result

Add the FS2 module to a JVM project. It brings in `frame4s-core` and supplies
the scoped execution boundary used below.

```scala
libraryDependencies += "io.github.canardlapin" %% "frame4s-fs2" % "@VERSION@"
```

Use `%%%` instead of `%%` in a Scala.js project.

The following program defines a schema as a named tuple, constructs two rows,
builds a pure query, executes it, and renders a bounded table.

```scala mdoc:silent
import cats.effect.{IO, IOApp}
import cats.effect.unsafe.implicits.global
import frame4s.*
import frame4s.fs2.*

type Observation = (id: Int, label: String, score: Option[Double])

val source = InMemoryFrameSource.rows[IO, Observation](
  Vector(
    (id = 1, label = "alpha", score = Some(4.0)),
    (id = 2, label = "東京", score = None)
  )
)

val query = source.frame
  .filter(row => row.col("id") > 0)
  .select(row =>
    (
      row.col("label"),
      row.col("score").sqrt.as("root")
    )
  )

def queryProgram: IO[String] =
  source.render(query, TableRenderOptions(maxRows = 5, maxWidth = 60))

object QuickStart extends IOApp.Simple:
  def run: IO[Unit] = queryProgram.flatMap(IO.println)
```

The documentation build runs the same `IO` value and records its result:

```scala mdoc
queryProgram.unsafeRunSync()
```

The query is an immutable `Frame` value. Constructing it performs no I/O.
`render` acquires the source, validates its schema, renders a bounded detached
string, and closes the materialized table before the `IO` completes. The raw
`label` column keeps its existing name; the computed square root needs the new
name `root`.

Next, [read typed CSV or TSV data](csv-tsv.md).
