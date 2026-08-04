# ADR-0004: Ergonomic typed public API

Status: accepted and implemented; release certification pending

Date: 2026-07-26

Decision issue: `bd-01KYG49Y07WBTF6SE79CHXPJB2`

## Context

The current public API preserves typed schemas, expression provenance, pure
plans, structured failures, and resource ownership. A routine one-source
program nevertheless repeats information that frame4s already has:

- exact Scala values must be wrapped with `Expr.literal`;
- a projected source column must repeat its existing name with `as`;
- callers construct a `SourceRef` for a source that is used alone;
- callers acquire `FrameRuntime` even when they only need a detached rendered
  string;
- routine table rendering requires an explicit conversion from
  `Either[TableReadError, String]` into the effect.

Removing all of this syntax indiscriminately would be unsound. Output names for
computed expressions are real schema decisions. A `Table` and emitted
`RecordBatch` values own storage and require visible scopes. Multiple sources
need stable identities. Numeric conversion and nullable values must remain
explicit.

The API therefore needs a rule stronger than making the shortest example
compile:

> Remove syntax only when frame4s already possesses the information. Keep
> syntax when the caller is choosing schema, conversion, nullability,
> ownership, or source identity.

The compiling contract court for this ADR lives in
`modules/first-contact/src/main/scala/example/ApiContractCourt.scala`.
It is in a downstream package and uses only the landed public frame4s API. Its
tests record the first compiler diagnostic for invalid operands, raw null,
duplicate names, unnamed computations, missing fields, provenance violations,
and join collisions.

## Decision

### Keep one transformation algebra

`Frame[S]` remains the only typed transformation algebra. `SourceBinding[F, S]`
may acquire and execute a `Frame`, but it will not define `filter`, `select`,
aggregation, join, sort, or column arithmetic. `Table[S]` remains a scoped read
view.

A query built for the concise route is the same `Frame` value accepted by
`FrameRuntime`. It can be named, passed to a function, conditionally composed,
normalized, explained, reused, and executed more than once.

### Accept exact Scala values as expression operands

`ExprOf[A, Origin]` accepts exact scalar operands whose required/nullable form
has the same physical scalar type:

```scala
row.col("id") > 0
row.col("score") === Option(1.5)
row.col("id") + 1
```

Each overload constructs `Expr.literal(value)` and calls the same resolved
expression builder as the expression-to-expression operation. Closed opaque
frame4s capabilities describe equality, ordering, arithmetic, division,
Boolean logic, numeric unary operations, and floating operations. Their result
type depends on both operands: a required scalar may combine with its `Option`
form and produces an optional result. No capability admits a different numeric
width. There is no implicit conversion, numeric widening, cast, open downstream
witness, or new expression node. Existing `LiteralExpr[A]` overloads remain
available for a reusable literal.

The experiment exposed a current hole: with Scala explicit-nulls mode disabled,
`column === null` compiles because `null` conforms to the reference type
`LiteralExpr[A]`. Each operand family will therefore include a more specific
inline `Null` overload. `Expr.literal(null)` receives the same overload. Both
report:

```text
Raw null is not a typed column value. Use None for a nullable column.
```

This overload is a compile-time rejection. It does not throw or manufacture a
typed null.

Scala also permits a project compiled without explicit nulls to manufacture an
invalid value such as `val value: String = null` or `Some(null)`. ADR-0008
supersedes this ADR's original handling of those cases. UTF-8 literals and
scalars now store a validated non-null wrapper. Checked constructors return
`ValueError.NullUtf8`; direct typed expression syntax performs the same check
and raises `InvalidValueFailure` before returning an expression or plan. The E1
and E5 courts retain the compile-time `Null` diagnostic and also exercise the
runtime boundary for an ascribed null.

### Preserve names on raw column expressions

`Scope.col("name")` currently returns `ExprOf[A, Origin]`, which erases the
singleton type of `"name"` after lookup. It will instead return a public
`ColumnExprOf[Name, A, Origin]` subtype. The subtype carries the already known
field name only; expression evaluation and provenance remain those of
`ExprOf`. Its constructor remains `private[frame4s]`, so only a checked
`Scope.col` lookup can create one and callers cannot forge a name or origin.

Projection and grouping selection will accept both:

- `ColumnExprOf`, whose output name is its existing field name; and
- `NamedExprOf`, used by renamed columns and computed expressions.

This allows:

```scala
frame.select(row => (row.col("name"), row.col("score")))
frame.groupBy(row => Tuple1(row.col("team")))
```

Arithmetic and other computed operations still return an unnamed `ExprOf`, so
the following remains required:

```scala
frame.select(row => Tuple1((row.col("id") + 1).as("nextId")))
```

The existing origin parameter remains on `ColumnExprOf`. A column captured
from another frame therefore remains a compile-time error. Existing
`UniqueNames` evidence checks raw and explicitly named selections together.
The compiler experiment confirms that a duplicate raw projection names the
duplicated field before exposing match-type details.

Selection evidence carries the inferred output-name and output-value tuples
directly. It does not leave a downstream `Frame` behind an unreduced
`SelectedSchema` match type. A caller may therefore keep a query in an
unannotated local `val`, collect it through a separately published artifact,
and use typed `Table.column`, `cell`, or `row` decoding without restating the
query's output schema.

### Add single-binding execution methods

`SourceBinding[F, S]` will expose only acquisition and execution:

```scala
binding.collect(query)            // Resource[F, Table[O]]
binding.stream(query)             // Stream[F, RecordBatch]
binding.render(query, options)    // F[String]
```

The canonical expansions are:

| Convenience | Expansion |
| --- | --- |
| `binding.collect(query)` | `FrameRuntime.resource(binding).flatMap(_.collect(query))` |
| `binding.stream(query)` | `Stream.resource(FrameRuntime.resource(binding)).flatMap(_.stream(query))` |
| `binding.render(query, options)` | `binding.collect(query).use(table => F.fromEither(table.show(options)))` |

`collect` keeps the `Table` inside `Resource`. `stream` keeps the runtime and
batches inside `Stream`. `render` may bracket internally because the returned
`String` owns no storage. Source acquisition, schema validation, pushdown,
execution, and finalization continue through `FrameRuntime`; these methods add
no logical or physical plan node.

Receipt-bearing execution, backend selection, and multiple bindings remain on
explicit `FrameRuntime`.

### Add constructors for a source used alone

The existing constructors that accept `SourceRef` remain the multi-source and
advanced forms. New overloads will cover the common single-source case:

```scala
InMemoryFrameSource.rows[IO, People](rows)
CsvFrameSource.binding[IO, People](csvText)
CsvPathSource.binding[IO, People](path)
TsvFrameSource.binding[IO, People](tsvText)
TsvPathSource.binding[IO, People](path)
```

The E1 signature court fixes these exact type-parameter and inference
boundaries. `F` and `S` may be supplied as shown; source settings, schema
descriptors, row codecs, and `Async[F]` continue to infer from the existing
public types. The explicit-reference overloads retain their current argument
order, so moving from one source to a join changes source construction rather
than the `Frame` transformation program.

These constructors use one deterministic reserved source identity and mark the
binding with a private `BindingIdentity` ADT whose cases are `Explicit` and
`SingleSource`. The implementation must not infer this state by comparing the
reference to a magic string or by adding a Boolean flag.
`FrameRuntime.resource` will reject an invocation containing more than one
binding when any binding has `BindingIdentity.SingleSource`. A dedicated
`RuntimeBindingError` case tells the caller to use the existing
explicit-`SourceRef` overload. This prevents a concise constructor from
silently choosing identity semantics for a join.

The reserved reference uses `SourceKind.Values` for rows and `SourceKind.Scan`
for CSV or TSV. It is stable and does not contain random state, an object
identity, a consumed iterator, or a content hash.

### Use progressive disclosure

The same types support three amounts of visible machinery:

1. A detached one-source result shows the schema, source, `Frame` query, and
   final effect.
2. An owned table or batch stream additionally shows `Resource` or `Stream`.
3. Multiple sources, receipts, or backend selection additionally show
   `SourceRef` and `FrameRuntime`.

The first form is not a wrapper that must be abandoned to use the other two.
The `Frame` query is unchanged.

## Target programs

The ordinary in-memory program becomes:

```scala
import cats.effect.{IO, IOApp}
import frame4s.*
import frame4s.fs2.*

object Example extends IOApp.Simple:
  type People = (id: Int, name: String, score: Option[Double])

  val rows = Vector(
    (id = 1, name = "Ada", score = Some(9.5)),
    (id = 2, name = "Lin", score = None)
  )

  val source = InMemoryFrameSource.rows[IO, People](rows)
  val selected = source.frame
    .filter(row => row.col("id") > 0)
    .select(row => (row.col("name"), row.col("score")))

  def run: IO[Unit] =
    source.render(selected).flatMap(IO.println)
```

A JVM CSV program changes only source construction:

```scala
val source = CsvPathSource.binding[IO, People](Path("people.csv"))
```

An owned result keeps its scope visible:

```scala
source.collect(selected).use { table =>
  IO.fromEither(table.column("name").left.map(TableReadFailure.apply))
}
```

An explicit join continues to construct distinct `SourceRef` values, bind both
sources, and acquire one `FrameRuntime.resource(left, right)`.

## Required laws

Implementation and release certification must prove:

- **Desugaring:** every convenience produces the same public primitive
  expansion documented above.
- **Safety:** no misspelled field, wrong operand type, duplicate output,
  cross-frame expression, raw null, or join collision becomes valid.
- **Composition:** concise queries remain reusable `Frame` values.
- **Ownership:** no source, cursor, batch, or table can escape its owner.
- **Single algebra:** neither `SourceBinding` nor `Table` gains transformation
  operations.
- **Diagnostics:** invalid programs name the caller-visible problem before any
  match type, origin, or helper evidence.
- **Parity:** concise and explicit execution produce identical plans, schemas,
  values, failures, pushdown, ordering, and finalization.
- **Cost:** convenience adds no plan nodes and no material execution work
  beyond its expansion.

## Considered alternatives

| Alternative | Decision |
| --- | --- |
| Rewrite only the README example | Rejected. It does not improve reusable programs or define safety and ownership boundaries. |
| Add `Dataset`, `FrameInput`, or an eager dataframe wrapper | Rejected. It creates a second algebra and a migration point between simple and advanced code. |
| Add implicit conversions from Scala values to expressions | Rejected. They weaken overload diagnostics and can hide numeric or nullable conversion. |
| Put transformation methods on `SourceBinding` | Rejected. Queries would stop being ordinary backend-neutral `Frame` values. |
| Infer computed output names from syntax | Rejected. The compiler does not possess a stable semantic field name for a computation. |
| Infer and bind CSV schemas automatically | Rejected. Runtime data cannot provide compile-time evidence without a separate inspected schema-generation step. |
| Derive multi-source identity from paths, hashes, or object identity | Rejected. Identity affects joins and receipts and therefore remains an explicit caller choice. |
| Make `render` return `Resource[F, String]` | Rejected. A detached `String` owns no resource; exposing the scope after it has served its purpose adds no safety. |

## Consequences

- Common programs remove redundant literals, aliases, runtime acquisition, and
  error lifting without losing the typed query.
- `ColumnExprOf` becomes a public implementation type, but it requires no
  annotation or import at call sites.
- Direct operands add overloads, so the downstream compile and diagnostic court
  remains mandatory. ADR 0012 extends that court through 128, 256, and 512
  columns and removes its dependence on repository inline settings.
- Single-source constructors cannot be combined in a multi-source runtime.
  Callers opt into the existing explicit-reference constructors when source
  identity matters.
- E2 owns expression operands and name-preserving columns. E3 owns
  single-binding constructors and execution. E5 admits both only after the
  downstream positive, negative, lifecycle, parity, and compile-cost court
  passes.
