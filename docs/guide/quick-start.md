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
type Selected = (label: String, root: Option[Double])

def checked[A](result: Either[FrameError, A]): IO[A] =
  IO.fromEither(
    result.left.map(error => new IllegalArgumentException(error.message))
  )

def queryProgram: IO[String] =
  checked(SourceRef.values("observations", "observations")).flatMap: reference =>
    val source = InMemoryFrameSource.rowsBinding[IO, Observation](
      reference,
      Vector(
        (id = 1, label = "alpha", score = Some(4.0)),
        (id = 2, label = "東京", score = None)
      )
    )
    val query: Frame[Selected] = source.frame
      .filter(row => row.col("id") > Expr.literal(0))
      .select(row =>
        (
          row.col("label").as("label"),
          row.col("score").sqrt.as("root")
        )
      )

    FrameRuntime
      .resource(source)
      .flatMap(_.collect(query))
      .use(table =>
        IO.fromEither(table.show().left.map(TableReadFailure.apply))
      )

object QuickStart extends IOApp.Simple:
  def run: IO[Unit] = queryProgram.flatMap(IO.println)
```

The documentation build runs the same `IO` value and records its result:

```scala mdoc
queryProgram.unsafeRunSync()
```

`Frame[Selected]` is an immutable logical query. Constructing it performs no
I/O. `FrameRuntime.resource` acquires the source, validates its schema, and
closes the materialized table after `use` finishes.

Next, [read typed CSV or TSV data](csv-tsv.md).
