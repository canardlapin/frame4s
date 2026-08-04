# frame4s architecture

Status: accepted implementation contract for the standalone foundation.

frame4s is an immutable, typed local dataframe library:

- package: `frame4s`
- core artifact: `frame4s-core`
- effectful adapter: `frame4s-fs2`
- optional JVM Arrow adapter: `frame4s-arrow`
- source modules: `modules/core`, `modules/fs2`, and `modules/arrow`

The project was extracted from ScalaFIM without changing its logical model,
execution contracts, or dependency direction. It uses neutral
`io.github.canardlapin` coordinates. No `org.typelevel` coordinates or
Typelevel project status are claimed; the intended path is an affiliate-first
proposal after the API, laws, maintenance model, and production evidence are
credible.

The accepted `0.1` scope and support contract are recorded in
[`../release-readiness-plan.md`](../release-readiness-plan.md) and
[`../release-policy.md`](../release-policy.md). The effectful execution boundary
is fixed by
[`adr-0001-execution-boundary.md`](adr-0001-execution-boundary.md), and typed
expression ownership is fixed by
[`adr-0002-expression-provenance.md`](adr-0002-expression-provenance.md).

## Thesis

The central value is:

```scala
Frame[Schema] // immutable typed logical plan
Expr[A]       // typed column expression
Table[Schema] // materialized columnar data
```

Selecting, adding columns, filtering, joining, grouping, aggregating, sorting,
and limiting construct a pure plan. They do not read, mutate, or materialize a
table. Execution is a separate interpretation step:

```scala
frame.explain
frame.stream[F]       // fs2.Stream[F, RowBatch[Schema]]
frame.collect[F]      // Resource[F, Table[Schema]]
```

This split makes query construction referentially transparent, permits plan
inspection and normalization before execution, keeps backend choice out of the
public query algebra, and gives the semantic reference interpreter a small,
deterministic testing surface.

## Module boundaries

### `frame4s-core`

The cross-built core has no external runtime dependency. It owns:

- Scala 3 named-tuple schema derivation and field lookup;
- `DynamicFrame` runtime-schema binding and promotion to `Frame[Schema]`;
- explicit `Option[A]` nullability in expression and output types;
- the expression algebra and immutable logical-plan ADT;
- Arrow-compatible logical types and immutable column/table contracts;
- pure plan explanation and normalization;
- a small semantic in-memory interpreter used for laws and tests.

It does not own filesystem or network IO, FS2, Cats Effect, Apache Arrow
allocators, Polars, DuckDB, or a production query optimizer.

### `frame4s-fs2`

This cross-built adapter depends on the core, Cats Effect, and FS2. It
owns effectful scan/sink boundaries, streaming execution, cancellation, and
resource-safe collection. A backend that lends buffers or holds file/native
handles must expose a `Resource`; a `Table` obtained through `collect` is valid
only within that resource scope. The reference backend may implement collection
with `Resource.pure`, but the public API does not weaken the lifetime contract.

Adapter failures follow one end-to-end contract. Ordinary source acquisition,
read/decode, upstream-stream, and close failures are `SourceError` values raised
as `SourceFailure`; `FrameRuntime` adds the `SourceId` without discarding the
cause. Ordinary sink upstream, write, and close failures remain in the declared
`SinkError` result. Cancellation and fatal platform errors are not converted.
Messages are bounded and do not render causes or rejected values. The decision
and court are recorded in
[`adr-0009-structured-adapter-failures.md`](adr-0009-structured-adapter-failures.md).

CSV and TSV are the portable textual adapters in `frame4s-fs2`. Other format-
and engine-specific integrations remain optional artifacts. Arrow, Parquet,
Polars, and SQL engines must not leak their types into the core algebra. Polars
is a gated candidate backend or collaboration, not the assumed execution
engine. DuckDB, Parquet, and Gale integrations remain separate decisions.

### `frame4s-arrow`

This JVM-only adapter depends on `frame4s-fs2` and Apache Arrow Vector. It owns
Arrow IPC stream encoding and decoding, Arrow allocators, and the JVM access
option required by Arrow's unsafe-memory implementation. Neither the JVM nor
Scala.js `frame4s-fs2` artifact depends on Arrow or inherits that runtime
requirement.

An `ArrowIpcFrameSource` snapshots the caller's byte array when its `Resource`
is constructed. Decoding adopts columns incrementally; every failure closes
the exact prefix already acquired, and failed `RecordBatch` validation closes
all decoded columns. For `0.1`, the adapter admits only timezone-free Arrow
microsecond timestamps because `TimestampMicros` is the only public typed
timestamp. Other units and timezone-bearing values fail with structured schema
or request errors. These decisions are fixed by
[`adr-0011-arrow-adapter-boundary.md`](adr-0011-arrow-adapter-boundary.md).

## Typed schema contract

A typed schema is a Scala 3.7 named tuple:

```scala
type People = (
  id: Int,
  name: String,
  score: Option[Double]
)
```

Field names are literal singleton types and field values carry their Scala
types. `SchemaDescriptor[People]` derives the ordered runtime schema. A dynamic
source is promoted only after exact ordered name, logical type, and nullability
validation:

```scala
dynamic.typed[People] // Either[FrameError, Frame[People]]
```

Typed lookup rejects missing names and incompatible expression types during
compilation. `Option[A]` is the only nullable field representation in the typed
surface. A nullable boolean cannot be used as a filter predicate until it is
made total, for example with `isTrue`. A left outer join maps every right-side
field to `Option`, without nesting an already optional field.

`ColumnType` is sealed, and every operator capability is a frame4s-owned closed
opaque token. The typed surface therefore admits only physical scalar
operations implemented by the interpreters; downstream `Numeric`,
`Fractional`, `Ordering`, or fabricated operator evidence cannot enlarge the
plan algebra. Binary result types account for both operands: equal-width
required and nullable scalars may be combined, and the result is nullable
exactly when either input is nullable. The dynamic surface validates the same
admitted algebra before constructing a plan.

UTF-8 literals and runtime scalars store `Utf8Value`, an opaque value created
only after a raw-null check. `Expr.literalChecked`, `LiteralValue.utf8`, and
`ScalarValue.utf8` return `ValueError` for untrusted input. Direct typed literal
and scalar syntax performs the same check before it returns an expression, so
an ascribed null or `Some(null)` cannot enter a logical plan. Row, schema,
source-order, and column-array constructors translate raw-null input into their
structured error channels; Arrow UTF-8 decoding preserves the same storage
check. The full boundary decision is recorded in
[`adr-0008-null-and-expression-identity.md`](adr-0008-null-and-expression-identity.md).

Each frame value has a path-dependent expression origin. Column expressions
created by its callback scope carry that origin, literals are origin-free, and
join expressions may carry only the union of the selected left and right
origins. The dynamic surface enforces the same ownership rule with private
per-frame tokens and returns `InvalidExpressionScope` before constructing a
plan. Provenance does not appear in expression IDs, schemas, or explain output.

Projection and aggregation derive new named-tuple schemas. `withColumn` adds a
fresh field and rejects accidental replacement; `replace` is the explicit
type-computing replacement operation. Joins require
disjoint output names in 0.1 so ownership is never resolved by implicit suffixes.

Schema descriptors, field lookup, and row codecs use flat compile-time
derivation rather than one inline or implicit expansion per preceding field.
The staged external court exercises 32, 48, 128, 256, and 512 columns on JVM
and Scala.js without inheriting the library's `-Xmax-inlines` setting. The
practical 0.1 envelope is 256 columns. A 512-column query and row round-trip is
verified as a stress tier, but its measured compile time is minutes rather than
seconds and is not presented as an ordinary-cost workflow. The API decision,
lowerings, and evidence boundary are recorded in
[`adr-0012-pre-freeze-api-and-wide-schema.md`](adr-0012-pre-freeze-api-and-wide-schema.md).

The initial physical-layout and allocation smoke receipt is recorded in
[`../benchmarks/storage.md`](../benchmarks/storage.md). It is scoped
to the semantic storage core and deliberately makes no production-engine or
universal zero-copy claim.

## Logical plan and errors

The closed 0.1 logical plan contains source, project, filter, join (inner,
left-outer, left-semi, and left-anti kinds), union-all, aggregate, sort, and
limit nodes. Right join lowers through left-outer join plus project; distinct
lowers through key-only aggregate. Convenience operations introduce no second
operation algebra.
Each node carries its validated output schema, making an invalid internal plan
unconstructable through the public typed API.

Resolved fields and expressions carry distinct `ColumnId` and `ExprId` values.
An `ExprId` contains a fixed-size structural fingerprint plus an internal
collision witness; neither is public diagnostic text. Logical explain assigns
deterministic plan-local ordinals and a linear, value-redacted expression
legend. Display names and left/right/current qualifiers remain separate. Scan
and literal-values nodes refer to immutable `SourceRef` identities rather than
capturing a table, cursor, backend, or closure. Public consumers can inspect a
plan through its output, node name, children, and deterministic explanation,
but the resolved node constructors remain internal. Typed erasure to
`DynamicFrame` and successful rebinding preserve the exact plan and schema
objects without copying data or executing the plan.

Failures are structured:

- `SchemaError` covers malformed runtime schemas;
- `ValueError` rejects raw null before UTF-8 literal or scalar construction;
- `BindingIssue` records ordered field count/name/type/nullability mismatches;
- `FrameError` covers binding and planning-boundary failures;
- `ExecutionError` records interpreter and storage failures without throwing
  them through the pure public boundary.

Internal `unsafe` constructors are permitted only after public validation or
compile-time derivation. They do not form part of the public API.

## Semantic constitution

The reference interpreter defines semantics independently of optional engines:

- Boolean expressions use SQL three-valued logic. `AND` and `OR` follow their
  SQL truth tables; filters retain only `true` and discard `false` and null.
- Ordinary equality with either operand null returns null. `nullSafeEq` is total:
  two nulls are equal, one null is unequal, and two values use logical equality.
- Null grouping keys form one group. Dictionary values compare by decoded
  logical value rather than dictionary index identity.
- NaN is a non-null floating value. IEEE equality applies (`NaN != NaN`); total
  ordering places NaNs after finite/infinite values and treats NaNs as one sort
  equivalence class. Floating reductions preserve logical input order, use
  ordinary IEEE arithmetic, and propagate NaN; any backend reordering tolerance
  must be declared in its receipt.
- `Int32` and `Int64` arithmetic is checked. Overflow and integral division by
  zero are structured `ExecutionError` values, never wrapping arithmetic.
- There are no implicit casts or numeric widening. The current closed
  expression algebra accepts equal physical scalar types and lifts required and
  nullable operands explicitly; future cast nodes must define overflow,
  precision, and null propagation per source/target pair.
- Timestamps are signed counts since the Unix epoch in their declared unit.
  Units do not compare or convert implicitly, and timestamps carry no hidden
  session timezone.
- UTF-8 strings use unsigned binary UTF-8 collation. Locale-sensitive collation
  requires a future explicit expression/capability.
- There is no hidden row index. `Values` sources are stable; scans are unordered
  unless their `SourceRef` declares an order. Project, filter, and limit preserve
  their input guarantee; aggregate and join do not; sort establishes an explicit
  key guarantee and is stable for equal keys.

### R4 grouping and distinct equivalence

Grouping compares decoded logical values, not physical encodings. Nulls form
one key class. All NaN payloads of the same floating width form one key class;
positive and negative zero form one key class. Non-NaN floating keys otherwise
use their IEEE bits. Timestamps require equal signed values and equal units.
UTF-8 keys require identical decoded strings. Dictionary indices and dictionary
identity never participate in equivalence. `distinct` is grouping by every
input field with no aggregate expressions, so it inherits exactly these rules
and declares `OrderGuarantee.Unspecified`.

### R4 union semantics

`UnionAll(left, right)` requires exactly equal ordered schemas, including field
names, data types, and nullability. It emits every left row followed by every
right row and preserves duplicates. The left branch is opened and evaluated
first; the right branch is not opened until the left is exhausted successfully.
A left failure suppresses right evaluation, and normalization never swaps the
branches. If both inputs guarantee stable order, concatenation is stable;
otherwise union order is unspecified. The reference cursor streams branch
batches without materializing both branches and closes the active branch plus
any opened successor on completion, failure, early termination, or
cancellation.

### R4 semi and anti join semantics

Left semi and left anti are explicit `JoinKind` values with exactly the left
schema. For each left row, the right side is examined in its logical order.
Semi emits the left row once when the first predicate result is true. Anti emits
the left row once only if no predicate result is true. False and null are both
non-matches; a predicate error before a true match is returned, while evaluation
short-circuits after the first true match. Right-side duplicates never multiply
an emitted left row, while duplicate left rows remain distinct input rows.
Empty-right semi is empty and empty-right anti returns every left row. Both
preserve the left input's order guarantee. There is no sentinel,
join-plus-distinct lowering, or implicit null test.

### R4 floating and population-statistic semantics

`sqrt` is available only for `Float`, `Double`, and their optional forms. It
preserves floating width and nullability, propagates null, and follows the JVM
and ECMAScript IEEE square-root result for signed zero, negative finite values,
NaN, and infinities. It is total: negative inputs produce NaN rather than a
structured domain failure, so error-preserving normalization may move or fuse
it when its input is total.

Population variance is named `variancePop`; no ambiguous `variance` alias is
part of the first compatibility baseline. `stddevPop` returns the square root
of the population variance result. Both ignore null observations, return null
for empty/all-null input, use denominator `N`, and return `Option[Double]`.
Sample variance and sample standard deviation are different future operations
and are never implied by these names.

`ReferenceInterpreter` is the always-available semantic oracle. Project,
withColumn/project lowering, filter, and limit transform record batches
incrementally. `ReferenceExecution.physicalExplain` is separate from pure
logical explain and reports streaming/blocking nodes, row estimates, and
`fallback=none`. It is deliberately not a production optimizer, spill engine,
SIMD framework, or performance competitor.

`ColumnarInterpreter` is a package-internal optimized engine, physically
separate from the oracle. It specializes admitted scan/projection,
filter/fusion, aggregation, join, distinct, and union shapes; unsupported
shapes use one explicit whole-plan reference fallback with a receipt. Its
detached results obey a close protocol, and reusable JVM/Scala.js differential
laws compare schemas, values, failures, and ordering with the oracle. The
physical vector boundary is isolated in `ColumnarVectors.scala`: raw borrowed
views, owned value vectors, selection/gather materialization, physical-buffer
copying, checksums, and the `RecordBatch` bridge remain package-private there.
`ColumnarInterpreter.scala` retains execution admission and kernels behind the
same facade. Unsafe indexed access and physical casts do not cross that
package-private performance boundary. The
public runtime's materializing `collect` executes through it (ADR-0005), the
engine decision is surfaced in the execution receipt rather than hidden
(ADR-0006), and `stream` uses the incremental reference route.

Engine selection is an effect-boundary policy, not ambient state.
`EnginePolicy.Auto` is the `SourceBinding` convenience default,
`ReferenceOnly` bypasses columnar execution, and `RequireColumnar` fails with a
structured `EnginePolicyError` when the optimized engine cannot answer.
`EngineReceipt` exposes an `EngineId` and typed `EngineFallbackReason`; only the
physical explain remains free-form diagnostic text.

`frame4s-fs2` binds immutable typed `SourceBinding` descriptions through one
invocation-scoped `FrameRuntime.resource`. All acquired sources are inspected
against their exact ordered typed schemas before execution. Multi-source
bindings support joins without a user-managed
`ReferenceSources`; that type remains an oracle-fixture boundary only.

For non-blocking source, project, filter, limit, and union plans,
`FrameRuntime.stream` pulls planned source batches on demand and retains only
the active operator pipeline. Limit stops upstream pulls at its row budget, and
union does not pull its right branch until the left branch ends. Aggregate,
join, and sort use an explicit materializing reference route. Both routes
release source state, cursors, and emitted batches on completion, failure,
early termination, or cancellation. `FrameRuntime.collect` retains output
batches into a `Resource[F, Table[Schema]]`; failed or canceled acquisition
closes every retained batch, and the resource finalizer closes the materialized
table. Already-owned columnar results transfer directly into that table;
batches from the scoped reference stream are detached before the stream scope
closes.
`streamPhysicalExplain` reports the reference stream's blocking boundary;
`physicalExplain` reports the engine selected for materializing collect.
`streamWithReceipt` and `collectWithReceipt` expose requested, accepted, and
residual source pushdown, and `collectWithReceipt` additionally carries an
`EngineReceipt` naming which engine answered, its physical plan, and any
typed fallback reason. Unsupported work stays in the logical reference path
under `Auto`; `RequireColumnar` rejects it.

CSV and TSV byte/character sources parse incrementally into bounded batches;
UTF-8 decoding and quoted records may cross arbitrary input chunks. JVM path
adapters use FS2 file resources, while Scala.js exposes only the portable
stream/string surface. Owning CSV and TSV sources are acquired through
`Resource`; the optional JVM `frame4s-arrow` adapter applies the same scoped
lifetime to Arrow IPC and its decoded buffers. Delimited cells retain
quotedness until decoding: configured
null tokens apply only to unquoted cells, while the writer quotes every real
value that could collide with that policy. Finite record, field, parser-chunk,
batch, and diagnostic limits make retained state independent of source chunk
size. One transport-leading BOM is ignored, blank records remain records, and
sink byte receipts count encoded UTF-8. These rules are fixed by
[ADR-0010](adr-0010-lossless-bounded-delimited-io.md).

`Table[S]` is a materialized read view, not another dataframe algebra. It can
decode detached named-tuple rows, exact typed cells/columns, and matching
`NamedTuple.From` products; construct an owned table from rows; and render
bounded rows/schema. It has no filter, project, sort, join, or arithmetic
transformation methods. Existing batches enter through one of two explicit
contracts: `takeOwnership` consumes them and closes all inputs on validation
failure, while `retainFrom` creates independent full-batch views and leaves the
caller's batches untouched. Row construction classifies iterator acquisition,
`hasNext`, `next`, encoding, and batch-building exceptions as structured input
failures and releases every batch or column allocated before the failure.
Ranges are validated with subtraction so signed integer wraparound cannot admit
an invalid buffer, column, or batch slice.

Normalization is observationally error-preserving as well as value-preserving.
Rewrites that reorder expression evaluation, including filter fusion and
project pushdown, are applied only when the expressions moved across the
boundary are total. Checked integral arithmetic therefore cannot begin failing,
or stop failing, merely because a plan was normalized.

## 0.1 delivery boundary

The accepted release plan is authoritative. The first usable release includes:

- immutable Arrow-compatible column storage and one semantic reference backend;
- typed projection, fresh-field `withColumn`, replacement, and filtering;
- inner, left, right, semi, and anti joins;
- group-by with count, sum, mean, population variance, min, and max;
- rename, drop, distinct, and `unionAll`;
- sort and limit;
- floating `sqrt` and population standard deviation;
- explicit missing-value semantics;
- one explicit FS2 source-binding/execution boundary with resource-safe
  streaming and collection;
- incremental in-memory, CSV, and TSV sources/sinks on JVM and Scala.js, plus a
  scoped JVM path entry point;
- typed materialized reading, row/case-class codecs, construction from rows,
  and bounded rendering without a second eager transformation algebra;
- Apache Arrow IPC stream ingestion/writing through the optional JVM
  `frame4s-arrow` adapter;
- pure plan display and normalization;
- reusable semantic/backend laws and honest JVM/Scala.js performance receipts.

Full outer joins, windows, reshape, generic first/last aggregates, ambient
runtime schema inference, Parquet, distributed execution, and a pandas-sized
convenience surface are later work.
The public runtime does include an in-process optimized columnar engine for its
admitted materializing plans. It is not a general spill engine, distributed
runtime, or claim of production-scale coverage beyond the ratified courts.
