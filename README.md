# frame4s

frame4s is an immutable, typed local dataframe library for Scala 3. A schema is
a type, a query is a pure value, and execution is one explicit resource scope.

```scala
import cats.effect.{IO, IOApp, Resource}
import fs2.io.file.Path
import frame4s.*
import frame4s.fs2.*

object Example extends IOApp.Simple:
  type People = (id: Int, name: String, score: Option[Double])
  type Selected = (name: String, score: Option[Double])

  private def checked[A](value: Either[FrameError, A]): IO[A] =
    IO.fromEither(value.left.map(error => new IllegalArgumentException(error.message)))

  def run: IO[Unit] =
    (for
      reference <- Resource.eval(checked(SourceRef.scan("people", "people.csv")))
      source = CsvPathSource.binding[IO, People](reference, Path("people.csv"))
      query: Frame[Selected] = source.frame
        .filter(row => row.col("id") > Expr.literal(0))
        .select(row =>
          (
            row.col("name").as("name"),
            row.col("score").as("score")
          )
        )
      runtime <- FrameRuntime.resource(source)
      table <- runtime.collect(query)
      rendered <- Resource.eval(
        IO.fromEither(table.show().left.map(TableReadFailure.apply))
      )
    yield rendered).use(IO.println)
```

The compiled downstream-package version of this workflow is
[FirstContact.scala](modules/first-contact/src/main/scala/example/FirstContact.scala).
It uses no handwritten runtime schema, manual batch, internal API, unsafe cast,
or `ReferenceSources`.

The central types are:

```scala
Frame[Schema] // immutable typed logical plan
Expr[A]       // typed column expression
Table[Schema] // materialized columnar data
```

## Modules

- `frame4s-core`: named-tuple schemas, typed expressions, logical plans,
  Arrow-compatible local storage, normalization, and the cross-platform
  semantic reference interpreter. It has no external runtime dependency.
- `frame4s-fs2`: Cats Effect and FS2 execution, scoped streaming and collection,
  CSV and TSV sources and sinks on JVM and Scala.js, and JVM Apache Arrow IPC.
- `frame4s-testkit`: a non-published, cross-built repository court containing
  deterministic generators, shrinkers, backend-conformance laws, and
  compile-time specimens.
- `frame4s-benchmarks`: a non-published JVM JMH court with raw versioned
  receipts and honest Saddle/specialized-baseline comparisons.
- `frame4s-first-contact`: a non-published downstream-package specimen that
  continuously proves the public CSV-to-typed-query-to-bounded-output path.

The reference interpreter defines semantics and provides a small useful local
backend. It is not intended to become a new production columnar engine.
Production integrations such as Polars, DuckDB, or Parquet belong in optional
adapters.

## Status

frame4s is an early extracted project and is not yet published. To try it
locally:

```sh
sbt publishLocal
```

The intended artifacts are:

```scala
libraryDependencies += "io.github.canardlapin" %%% "frame4s-core" % version
libraryDependencies += "io.github.canardlapin" %%% "frame4s-fs2" % version
```

No released version is implied by the snapshot version in the build.

## Development

The supported `0.1` build and execution court is deliberately narrow:

- Eclipse Temurin JDK 21.x for JVM compilation, tests, and execution;
- Scala 3.7.4;
- sbt 1.10.5 as fixed by `project/build.properties`;
- Node.js 24.x for Scala.js compilation and tests;
- sbt-scalajs 1.22.0 with CommonJS output under that Node.js runtime.

Other JDK, Node.js, Scala, sbt, and Scala.js versions may work, but are not part
of the `0.1` support claim until they have an explicit CI and staged-consumer
receipt.

Run every supported platform:

```sh
sbt compileAll testAll
```

CI additionally compiles the generated JMH harness with `benchmarkSmoke`.
Performance claims are governed by the
[measurement court](docs/benchmarks/court.md) and
[ratified budgets](docs/benchmarks/budgets.md); the published receipt includes
the workloads frame4s currently loses.

The semantic contract, resource ownership rules, and deliberate scope boundary
are described in [the architecture](docs/design/architecture.md).
The typed/dynamic surface, lowering, order guarantees, and edge cases are
summarized in the [public operation map](docs/operations.md).
The exact toolchain, compatibility, API-freeze, and maintainer-continuity
promises are defined by the [0.1 release policy](docs/release-policy.md).
The executable [user guides](docs/guide/quick-start.md), detailed
[compatibility policy](docs/compatibility.md), and
[release checklist](docs/release-checklist.md) describe the candidate court.

## Typelevel relationship

frame4s is designed in the Typelevel ecosystem and uses Cats Effect and FS2 in
its effectful adapter. It is not currently a Typelevel affiliate or
organization project, and it does not claim `org.typelevel` coordinates. An
affiliate application is a future governance decision after the project has
credible maintenance, release, security, compatibility, and production
evidence.

## Provenance and license

frame4s was extracted from the independent Frame modules originally developed
in [canardlapin/scalafim](https://github.com/canardlapin/scalafim). See
[PROVENANCE.md](PROVENANCE.md) for the source boundary and audit record.

Licensed under the [Apache License 2.0](LICENSE).
