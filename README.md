# frame4s

frame4s is an immutable, typed local dataframe library for Scala 3. A query is
a pure logical program; execution is explicit and effectful.

```scala
import frame4s.*

type People = (
  id: Int,
  name: String,
  score: Option[Double]
)

val people = Frame.source[People]("people").toOption.get

val plan = people
  .filter(row => row.col("score").isNull || (row.col("id") > Expr.literal(0)))
  .select(row => (row.col("id").as("id"), row.col("name").as("name")))

plan.explain
```

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
  CSV sources and sinks on JVM and Scala.js, and JVM Apache Arrow IPC.

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

Requirements:

- JDK 21 or newer;
- sbt 1.10.5;
- Node.js for Scala.js tests.

Run every supported platform:

```sh
sbt compileAll testAll
```

The semantic contract, resource ownership rules, and deliberate scope boundary
are described in [the architecture](docs/design/architecture.md).

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
