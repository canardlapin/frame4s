# frame4s

frame4s is an immutable, typed local dataframe library for Scala 3. A schema is
a type, a query is a pure value, and execution is one explicit resource scope.

```scala
import cats.effect.{IO, IOApp}
import frame4s.*
import frame4s.fs2.*

object Example extends IOApp.Simple:
  type People = (id: Int, name: String, score: Option[Double])

  val source = InMemoryFrameSource.rows[IO, People](
    Vector(
      (id = 1, name = "Ada", score = Some(9.5)),
      (id = 2, name = "Lin", score = None)
    )
  )

  val query = source.frame
    .filter(row => row.col("id") > 0)
    .select(row => (row.col("name"), row.col("score")))

  def run: IO[Unit] = source.render(query).flatMap(IO.println)
```

The result is a bounded table:

```text
|name|score|
|----|-----|
|Ada |9.5  |
|Lin |null |
```

The compiled downstream-package version of this workflow is
[FirstContact.scala](modules/first-contact/src/main/scala/example/FirstContact.scala).
It uses no handwritten runtime schema, source identity, runtime setup, manual
batch, internal API, or unsafe cast.

Read the executable [three-minute quick start](docs/guide/quick-start.md), then
continue to [practical dataframe pipelines](docs/guide/practical-pipelines.md),
[typed CSV and TSV](docs/guide/csv-tsv.md), or
[execution and ownership](docs/guide/execution-and-ownership.md).

The central types are:

```scala
Frame[Schema] // immutable typed logical plan
Expr[A]       // typed column expression
Table[Schema] // materialized columnar data
```

## Practical pipelines

`Frame` supports the common local-dataframe workflow without introducing a
mutable dataframe API:

- `filter` keeps rows selected by a typed Boolean expression;
- `select` computes an exact output schema from named expressions;
- `withColumn` appends a derived column to a new `Frame`;
- `replace` derives a new value for an existing column; and
- `groupBy(...).aggregate(...)` computes typed summaries.

Nullable columns use `Option[A]`. Nullable arithmetic requires an explicit
nullable operand, so `row.col("height") / Some(100.0)` returns
`Option[Double]` and propagates missing values. See the
[practical-pipelines guide](docs/guide/practical-pipelines.md) for a complete
filter, derived-column, projection, and grouped-summary example.

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
  receipts and matched comparisons with Saddle, Pandas, data.table, and dplyr.
- `frame4s-first-contact`: a non-published downstream-package specimen that
  continuously proves the public typed-source-to-query-to-bounded-output path.

The reference interpreter defines semantics and provides a small useful local
backend. It remains the backend used by the public `FrameRuntime`. A separate
internal columnar candidate now covers general typed expression pipelines and
projected nullable grouping, but it is benchmark and conformance evidence for
future backend work, not a public runtime selection. Production integrations
such as Polars, DuckDB, or Parquet still belong in optional adapters.

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

The current 10,000-row
[practical-pipeline receipt](docs/benchmarks/receipts/2026-07-29-dplyr-practical/dplyr/summary.md)
records the internal columnar candidate at `0.487 ms/op` for
filter/derived-column/projection and `0.722 ms/op` for projected nullable
grouping. The corresponding dplyr medians were `1.340 ms` and `1.594 ms` in a
separate R process. These descriptive ratios do not imply that public
`FrameRuntime` calls use the candidate.

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
