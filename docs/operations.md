# Operations and semantics

This is the public operation map for frame4s 0.1. `Frame[S]` is the only
transformation algebra. `Table[S]` is an owned read view: it decodes rows,
cells, columns, and case classes and renders bounded output, but it does not
filter, project, aggregate, sort, or join. `Table.takeOwnership` explicitly
transfers existing batches; `Table.retainFrom` gives the table independent
retained views while preserving caller ownership. `Table.fromRows` reports
nonfatal iterator and row-encoding failures through `TableReadError` and cleans
all partial allocations.

## Operation matrix

| Public operation | Typed result | Dynamic failure | Logical form | Order |
| --- | --- | --- | --- | --- |
| `select` | schema from named expressions | duplicate names, invalid scope | `Project` | preserves input |
| `withColumn` | appends one fresh field | collision, invalid scope | `Project` | preserves input |
| `rename`, `renameAll` | changes names in place | missing, duplicate request, or output collision | `Project` | preserves input |
| `drop`, `dropAll` | removes fields in input order | missing or duplicate request | `Project` | preserves input |
| `replace` (typed and dynamic), dynamic `replaceAll` | changes values/types in place | missing, duplicate request, or invalid scope | `Project` | preserves input |
| `filter` | unchanged | wrong/null boolean or invalid scope | `Filter` | preserves input |
| `distinct` | unchanged | none after binding | key-only `Aggregate` over every field | unspecified |
| `groupBy(...).aggregate(...)` | keys followed by aggregates | duplicate names, invalid expressions | `Aggregate` | unspecified |
| `innerJoin` | left fields then right fields | name/type/scope errors | inner `Join` | unspecified |
| `leftJoin` | left then nullable right | name/type/scope errors | left-outer `Join` | unspecified |
| `rightJoin` | nullable left then right | name/type/scope errors | swapped left-outer `Join` plus `Project` | unspecified |
| `semiJoin` | left schema | predicate/scope errors | left-semi `Join` | preserves left |
| `antiJoin` | left schema | predicate/scope errors | left-anti `Join` | preserves left |
| `unionAll` | exact same ordered schema | ordered schema mismatch | `UnionAll` | stable only when both inputs are stable |
| `sortBy` | unchanged | empty sort on dynamic path | `Sort` | sorted, stable for equal keys |
| `limit` | unchanged | negative count | `Limit` | preserves input |

The typed multiple-column projection conveniences accept arbitrary non-empty
tuples of closed request values. `renameAll` resolves every source against the
original schema and applies all names in one project, so swaps are atomic;
`dropAll` removes every requested source in one project. Missing sources,
duplicate sources, duplicate targets, and collisions with unrenamed fields are
compile errors. The dynamic variants accept an arbitrary non-empty request list and return
`FrameError.DuplicateColumnRequests`; dynamic `replaceAll` has the same
request rule. More complex typed projections use one `select`, which computes
the exact output schema without adding another algebra.

```scala
type Input = (id: Int, label: String, score: Option[Double])
type Output = (key: Int, name: String, score: Option[Double])

def renamed(input: Frame[Input]): Frame[Output] =
  input.renameAll(
    (
      RenameRequest("id", "key"),
      RenameRequest("label", "name")
    )
  )
```

## Missing values and comparison

`Option[A]` is the only nullable typed column. Ordinary comparison follows SQL
three-valued logic: a null operand produces null. Filters require a total
boolean, so nullable comparisons must be made total explicitly. `isTrue` keeps
only true, `isFalse` keeps only false, `isNull` and `isNotNull` test presence,
and `nullSafeEq` compares two values without producing null. Nullable and
required Boolean expressions may be combined directly with `&&` and `||`; the
result is nullable whenever either operand is nullable and follows the complete
SQL three-valued truth table. Null grouping keys coalesce.

Distinct and grouping compare logical values. Dictionary identity is ignored;
UTF-8 strings compare as decoded strings; timestamps require the same value and
unit; all NaN payloads of one width coalesce; and positive and negative zero
coalesce. Distinct does not promise which physical representative of an
equivalence class is returned.

## Joins

Join output names must be disjoint. frame4s never invents suffixes. Right join
is deliberately a theorem of left join: the inputs are swapped and one
projection restores the public left-then-right field order. There is no
`RightOuter` plan node.

Semi and anti joins use existential truth. A semi join emits a left row once
when any right-row predicate is true. An anti join emits it once when no
predicate is true. False and null do not count as matches, right duplicates
never multiply left rows, and evaluation stops after the first true result.

Typed using joins accept a non-empty tuple of `UsingKey` values. Each name is
resolved independently on the left and right, so physical field order may
differ; each corresponding typed-column representation must match exactly.
Keys are compared in request order. The output keeps every left field followed
by the right fields that are not using keys, and one ordinary `Join` node is
constructed.

```scala
left.innerJoinUsing(
  right,
  (UsingKey("account"), UsingKey("day"))
)
```

## Sorting

Each typed sort key carries its own direction and null placement. Keys are
applied from left to right, equal rows retain input order, and the convenience
always lowers to one `Sort` node.

```scala
input.sortBy(
  row => SortKey(row.col("account")),
  row => SortKey(row.col("score")).descending.nullsFirst
)
```

## Union and branch failures

`unionAll` requires equal field count, ordered names, data types, and
nullability. It preserves duplicates and consumes the left branch fully before
opening the right branch. A left failure suppresses right evaluation.
Normalization does not exchange the branches. The reference interpreter
streams active-branch batches and releases them on completion, failure, early
termination, or cancellation.

## Numeric operations

Integral arithmetic is checked and returns structured overflow or
divide-by-zero failures. Integral division truncates toward zero. The
`MinValue / -1` cases are reported as overflow rather than wrapping. There are
no implicit casts or numeric widening.

`+`, `-`, `*`, and `/` accept `Int`, `Long`, `Float`, `Double`, and their
`Option` forms. Both operands must have the same underlying physical width.
Required and nullable operands combine lawfully: either nullable operand makes
the result nullable, so `Expr[Option[Int]] + Expr[Int]` has type
`Expr[Option[Int]]`. A null operand propagates null.

Planning uses frame4s-owned closed opaque capabilities rather than Scala's open
`Numeric`, `Fractional`, or `Ordering` type classes. User-supplied evidence
cannot authorize an expression that the physical algebra does not implement.
The dynamic expression surface validates the same matrix and returns
`UnsupportedUnaryExpression` or `UnsupportedBinaryExpression` before a plan is
constructed.

Unary `-` accepts every numeric width and nullable form. Checked integral
negation reports overflow for `Int.MinValue` and `Long.MinValue`; floating
negation follows IEEE behavior.

`sqrt` exists only for `Float`, `Double`, `Option[Float]`, and
`Option[Double]`; it preserves width/nullability and follows IEEE behavior,
including signed zero, NaN, negative inputs, and infinities.

Population variance and standard deviation are named `variancePop` and
`stddevPop`. They use denominator `N`, ignore null observations, and return
null for empty or all-null inputs. There is intentionally no ambiguous
`variance` alias and no silent sample-statistic interpretation.

Aggregate result types remain truthful for every input cardinality: `count`
returns `Long`; `sum`, `min`, and `max` return the input scalar wrapped exactly
once in `Option`; and `mean`, `variancePop`, and `stddevPop` return
`Option[Double]`. Required input columns do not make a reduction result required,
because a global reduction can still receive no rows.

## Plans and execution

The closed public plan has `Source`, `Project`, `Filter`, `Join`, `UnionAll`,
`Aggregate`, `Sort`, and `Limit`. Convenience operations above explain as
those nodes. Logical explain is pure and deterministic. It uses plan-local
expression ordinals and a linear operator legend; literal data and internal
fingerprints are never rendered. Physical explain names
the selected backend, physical operators, blocking behavior, estimates, and
fallback. The reference interpreter is the semantic oracle. Execution,
streaming, and materialization occur only through an explicit scoped runtime.

Collection and streaming have deliberately different physical contracts.
`FrameRuntime.collectWithReceipt` under `Auto` selects an admitted columnar
kernel or records a typed reason for reference fallback. `ReferenceOnly`
bypasses that selection, while `RequireColumnar` rejects a declined plan.
`FrameRuntime.streamWithReceipt` uses the reference cursor: source, project,
filter, limit, and `unionAll` pull incrementally; aggregate, join, and sort
materialize before emitting output.

The public evidence boundary is explicit:

| Question | Evidence |
| --- | --- |
| Which backend actually collected the result? | `MaterializedExecution.receipt.engine`, containing `EngineId`, the physical plan, and an optional typed fallback reason |
| Which source capabilities were accepted or left residual? | `ExecutionReceipt.sources` and each `SourceExecutionReceipt.pushdown` |
| Which columns did a source report reading? | `PushdownReceipt.columnsRead` |
| Where will a stream block? | `FrameRuntime.streamPhysicalExplain`; `physicalExplain` instead describes materializing collection under the selected policy |

The general runtime receipt does not currently report rows read or peak
retained bytes. The streaming conformance court instruments its source to prove
early emission, exact pull counts, and zero active batches after completion,
failure, and cancellation. Its memory assertion counts only the active value
buffers of a required `Int32` fixture; it is not allocator-wide or heap-peak
telemetry. No broader memory claim is made for `0.1`.

The normative edge cases and ownership rules are in the
[semantic constitution](design/architecture.md#semantic-constitution).

## Schema-width envelope

The staged external court compiles against published `frame4s-core`
coordinates with ordinary consumer compiler settings; it does not inherit the
library build's `-Xmax-inlines` value. JVM and Scala.js specimens at 32, 48,
128, 256, and 512 columns cover descriptor derivation, final-field lookup,
projection, atomic rename/drop, grouping, multikey joins, heterogeneous sort,
and row encoding/decoding. The 32-column specimen also checks representative
missing, duplicate, and mistyped-key diagnostics from outside package
`frame4s`.

The practical 0.1 envelope is 256 columns. In a focused warm H9 development
court, each 256-column task completed in about six seconds and the 512-column
tasks took 217 seconds on the JVM and 198 seconds on Scala.js. The stronger
isolated published-coordinate rehearsal started each width in a fresh sbt
process: 256 columns took 35 seconds on the JVM and 31 seconds on Scala.js; 512
columns took 254 and 275 seconds. Treat 512 as a verified stress tier, not an
ordinary compile-cost promise. Peak compiler memory was not isolated and is not
claimed. The exact release SHA must reproduce the complete ladder before
tagging. No final library width failure was observed through 512 columns;
widths above 512 were not tested, so frame4s does not claim a first failing
width beyond the practical 256-column envelope.

## Adapter failures

Source inspection and planning return `Either[SourceError, A]`. Once a source
resource or batch stream is active, ordinary nonfatal failures are raised as
`SourceFailure`; a bound runtime translates them to
`RuntimeBindingFailure` while retaining source identity and cause. Built-in
sinks return ordinary upstream, write, and close failures as `Left(SinkError)`.
Malformed byte input has its own `InvalidUtf8` case and is never decoded with
replacement characters. Cancellation and fatal errors remain effect-level.

Public error messages are deterministic, control-safe, and at most 256
characters. They omit throwable messages and rejected scalar contents. Causes
remain available through `cause` and `getCause` for deliberate debugging.

## Delimited I/O

CSV and TSV preserve the distinction between syntax and data. `NullPolicy`
recognizes tokens only in unquoted cells; quoted empty strings and quoted token
strings are ordinary values. Sinks emit null with the chosen unquoted token and
force quotes around any real scalar that could collide with the policy,
including whitespace-sensitive and leading-BOM text. A blank physical record
is one empty field. Only a BOM at the first transport character is removed.

`DelimitedReadLimits` places finite bounds on logical records, decoded fields,
and retained error excerpts. The parser also caps its own input slices and
rejects an excess field at the delimiter that begins it. Locations use
one-based record and field numbers with a zero-based decoded-character offset;
public rendering redacts the bounded excerpt. Sink receipts count encoded UTF-8
bytes rather than Scala string length.
