# frame4s 0.1 release-readiness plan

Status: accepted; E0 ergonomic API amendment active

Ratified: 2026-07-26 through Mote issue
`bd-01KYF1PJC3K8NRGDNNT47QJFF2`

E0 amendment: 2026-07-26 through Mote epic
`bd-01KYG47EQY15E3XQB9EWDZ3WXG`

Normative companion records:

- [`release-policy.md`](release-policy.md) fixes the supported toolchain,
  compatibility, API-freeze, and release-continuity promises.
- [`design/adr-0001-execution-boundary.md`](design/adr-0001-execution-boundary.md)
  fixes the source-binding, backend-selection, pushdown, and ownership boundary.
- [`design/adr-0002-expression-provenance.md`](design/adr-0002-expression-provenance.md)
  fixes typed and dynamic expression ownership.
- [`design/adr-0004-ergonomic-api.md`](design/adr-0004-ergonomic-api.md)
  records which one-source conveniences may derive redundant evidence and
  which schema, nullability, ownership, naming, and source-identity decisions
  remain explicit.

Target: first public `0.1.0` release

Date: 2026-07-26

## Purpose

This plan turns the frame4s design identity into an executable release
program:

> frame4s is the local dataframe library where the schema is a type, the query
> is a value, and semantics are laws—engineered and measured so rigor does not
> become a tax on speed or usability.

The goal is not dataframe feature parity. The goal is a small, coherent local
dataframe library whose public claims are true, whose ordinary workflow has no
dead ends, and whose optimized execution is governed by the semantic oracle.

`0.1.0` is release-ready only when:

1. the public API cannot construct any currently known constitution-violating
   plan;
2. a routine one-source program does not require the caller to restate a
   literal wrapper, an unchanged source-column name, a source identity that
   cannot conflict, runtime acquisition, or error lifting that frame4s can derive
   without changing semantics;
3. a new user can construct or read typed data, inspect it, build a reusable
   query value, and execute that query without internal APIs or manual source
   rebinding;
4. every supported operation has explicit null, NaN, overflow, ordering, and
   failure semantics in the reference interpreter;
5. every optimized path included in the release is differentially checked
   against that interpreter;
6. performance and compile-time claims have reproducible receipts, including
   losses;
7. the JVM and Scala.js artifacts, documentation, security policy, provenance,
   and publication process have all been rehearsed from a clean checkout.

## Product and scope rules

### The single-algebra rule

`Frame[S]` is the only transformation algebra. `Table[S]` is an owned,
materialized read/interop value. It may expose bounded inspection, typed
decoding, rendering, and construction, but it must not grow eager filter,
sort, join, arithmetic, or aggregation methods.

### The admission rule

Every proposal must fit exactly one of these categories:

| Category | Admission test | Required evidence |
| --- | --- | --- |
| Derived convenience | Lowers to the existing closed plan without new observable semantics | Lowering test, typed-schema test, and identical oracle result |
| Foundational extension | Adds a node or operator that materially completes the small relational algebra | Constitution amendment, oracle implementation, laws, explain/order contract, and backend conformance |
| Boundary adapter | Adds ingestion, output, rendering, or interoperability without changing the algebra | Structured errors, exact capability receipt, scoped ownership, and integration example |
| Feature accretion | Adds a new semantic domain without strengthening the release identity | Defer or reject |

A standard pandas, Polars, Spark, or SQL operation is not admitted merely
because it is familiar.

### Committed `0.1.0` scope

- Close all five known constitutional defects.
- Establish reusable semantic, normalization, ownership, and backend
  conformance laws.
- Establish a JVM benchmark court and a separate Scala.js performance receipt.
- Unite `FrameSource`, source binding, query execution, and `FrameRuntime`
  behind one explicit resource boundary.
- Add a deliberately small materialized read surface:
  typed row/cell/column decoding, `fromRows`, bounded `show`, and named-tuple /
  case-class codecs.
- Replace whole-string CSV/TSV ingestion with incremental ingestion and add a
  scoped JVM path entry point while retaining portable stream/string entry
  points.
- Add the high-value relational surface that either lowers to the existing
  plan or completes its basic local relational algebra:
  rename, drop, replace, distinct, right join, `unionAll`, semi join, anti join,
  floating `sqrt`, and population standard deviation.
- Publish signed JVM and Scala.js artifacts with executable documentation,
  provenance, security, compatibility, and release receipts.

### Claim-gating decision

The optimized backend is not a `0.1.0` release gate. The first public release
may ship with the semantic reference interpreter and honest comparative
receipts, but it must not claim competitive speed. R5 gates an optimized
backend artifact and comparative performance claims, not the factual
availability of `0.1.0`.

An optimized path may be included in `0.1.0` only if it independently satisfies
all R5 criteria. Otherwise it remains a post-`0.1.0` performance track. This
choice prevents four uncertain kernel families from holding the semantic and
ergonomic release hostage while preserving the rule that no unearned speed
claim is published.

### Explicitly deferred

- full outer join;
- sample variance and sample standard deviation;
- generic first/last aggregates;
- windows;
- pivot, melt, and other reshape families;
- temporal expansion and resampling;
- `describe` and a pandas-sized summary API;
- ambient runtime schema inference that directly manufactures a typed frame;
- Parquet, Polars, DuckDB, and distributed adapters;
- spill, distributed execution, and a production engine inside
  `ReferenceInterpreter`;
- serialized logical plans as a public wire format;
- a Scala Native support promise;
- Typelevel affiliation or `org.typelevel` coordinates.

An explicit schema-generation CLI or compile-time fixed-asset contract may be
planned after `0.1.0`. Any such facility must emit or embed an inspectable
schema receipt and validate the runtime header/fingerprint before typed
binding.

## Baseline at ratification

At ratification, the checkout already had several strong foundations:

- a portable named-tuple schema, expression, and closed-plan core;
- exact typed-to-dynamic rebinding checks;
- SQL three-valued logic, checked integral arithmetic, documented null/NaN
  behavior, and error-preserving normalization;
- immutable Arrow-compatible storage with explicit close/retain behavior;
- a semantic reference interpreter;
- Cats Effect/FS2 resource and cancellation tests;
- JVM and Scala.js compilation and tests under `sbt compileAll testAll`;
- strict compiler warnings with `-Werror`;
- public-package compile probes and 32-column schema coverage;
- governance, provenance, security, and ecosystem-readiness drafts.

The release blockers visible at plan ratification were:

- five tests deliberately marked as expected failures;
- `Table` exposes only schema, row count, close state, and close;
- `FrameSource` can inspect and plan scans, but `FrameRuntime` executes only
  tables manually placed in `ReferenceSources`;
- CSV/TSV decoding accepts an entire `String` and materializes all parsed rows
  before the source can be scanned;
- performance evidence consists of bounded smoke programs rather than JMH or
  comparative receipts;
- there is no property-generator or reusable backend-conformance layer;
- CI is a single JDK job and has no format, staged-artifact, documentation,
  benchmark-smoke, compatibility, or publication gate;
- there is no configured formatting, compatibility, coverage, or release
  workflow;
- the readiness policy requires two release-capable maintainers, while the
  current project has one known maintainer.

## Dependency order

```text
R0 -> R1
R1 -> R2 Laws and measurement court
R1 -> R3 Execution boundary and first use
R1 -> R4 Small algebra completion
R2 + R3 + R4 -> E0 Ergonomic API gate -> R6 API freeze -> R7 Release candidate

R2 -> R5a Existing-algebra optimized kernels and claim gate
R2 + R4 -> R5b Union/distinct/semi/anti optimized kernels
```

Release plumbing that does not freeze public APIs may proceed in parallel, but
no new public transformation surface lands before R1. No optimized kernel
lands before its R2 oracle and benchmark workload exist. R5 is deliberately not
on the `0.1.0` release-candidate critical path.

### Effort scale and visible risks

Sizes are focused maintainer effort, not calendar promises:

- **S**: one or two maintainer-days;
- **M**: three to five maintainer-days;
- **L**: six to ten maintainer-days;
- **XL**: more than ten maintainer-days and must be decomposed after a spike.

| Work | Initial size | Principal uncertainty |
| --- | --- | --- |
| R1.1 provenance design spike | M, hard timebox of three maintainer-days | Scala 3 scope identity, diagnostics, and composition ergonomics |
| R1.1 selected implementation | L or XL; re-estimate from ADR | Public expression signatures and join callbacks |
| R1.2 output-name uniqueness | M | Compile-time uniqueness evidence and readable diagnostics |
| R1.3 join internal identity | M | Removing sentinel names without destabilizing using joins |
| R1.4 IEEE mean | S | Exact infinity/NaN and empty-group policy |
| Initial R2 generators/law skeleton | M | Useful shrinking across schemas and owned storage |
| Initial R2 JMH court | M | Equivalent Saddle workloads and reproducible fixtures |
| R5 scan/project and fusion | M each | Batch layout and allocation accounting |
| R5 hash aggregation | L | Null/NaN grouping equivalence and skew |
| R5 hash join | XL; split by join kind | Cardinality, ownership, and skew |
| R5 union/distinct/semi/anti follow-on | M to L after R4 | New-node/order semantics and reuse of earlier kernels |

## R0 — Ratify the release contract

### Work

1. Adopt this plan and the admission rule as the release scope.
2. Record the exact supported Scala, JDK, Node, sbt, JVM, and Scala.js ranges.
3. Define the backend extension boundary needed by R2 conformance tests. The
   packaging decision for an optimized backend may wait until R5, but it must
   not be implemented inside `ReferenceInterpreter`.
4. Write a short execution-boundary ADR covering:
   - how a typed `Frame[S]` receives a stable `SourceRef`;
   - how one or more `FrameSource[F]` values are bound for joins;
   - how an execution backend is selected;
   - where pushdown is negotiated;
   - who owns and closes every source, cursor, batch, and table.
5. Adopt the explicit single-maintainer continuity policy for `0.1.0`.
   `canardlapin` owns the release path. R6 blocks a candidate until an offline
   recovery/break-glass rehearsal has produced a non-secret public receipt.
   Adding a second release-capable maintainer remains desirable but is not
   misrepresented as current capacity or made an implicit late release gate.
6. Freeze additions to the public transformation API until R1 is complete.

### Acceptance criteria

- The release scope has one maintainer-approved list of committed and deferred
  capabilities.
- The extension-boundary/ADR decision preserves a dependency-free,
  cross-platform core and a separate semantic reference interpreter.
- The maintainer/access risk has a named owner, a chosen policy, and an R6
  verification gate; it is not left as a late publication surprise.
- Query construction remains pure and performs no source acquisition.
- No global session, ambient filesystem, hidden row index, or implicit backend
  is introduced.
- The supported toolchain and compatibility promise are stated in the README
  and release policy without claiming untested versions.

## R1 — Close every constitutional contradiction

R1 is a hard gate. It may change public signatures because `0.1.0` has not yet
established a compatibility baseline.

### R1.1 Expression provenance

Prevent an expression captured from one scope from being used against another
frame, including two frames with the same field names and types in different
orders.

Begin with a design spike, hard-timeboxed to three maintainer-days. It must
produce small compiling experiments and an ADR comparing at least:

- path-dependent frame/scope identities;
- phantom scope tags carried by `Expr`;
- a rank-2 or otherwise scoped callback/builder;
- pure plan-construction validation returning a structured error.

The ADR must compare ordinary filter/project syntax, reusable literals, joins,
captured helper functions, 32-column compile cost, and the first compiler
diagnostic for foreign-scope use.

Decision rule:

- choose compile-time rejection if a mechanism prevents smuggling without
  exposing scope tags, match-type internals, or materially degrading ordinary
  composition;
- otherwise use the pure structured-planning-error design as the safety floor,
  with the affected method returning an explicit result before any
  `LogicalPlan` is authorized;
- never defer the mismatch to execution and never preserve the current
  signature by throwing.

The fallback is not permission to stop investigating stronger static
provenance. It is a bounded release decision that prefers visible, structured
failure over silent corruption or an indefinitely blocked program.

Acceptance criteria:

- The existing cross-frame-smuggling example is either rejected at compile
  time under the selected static design or returns the selected structured
  planning error before producing a frame.
- Literal and explicitly reusable scalar expressions remain comfortable to
  compose.
- Join predicates admit only the corresponding left/right scopes.
- Dynamic expression misuse returns a structured planning error before
  execution.
- There is no public cast or public constructor that can forge expression
  provenance.
- Under a static design, negative compile tests assert that the first useful
  diagnostic names the scope problem rather than exposing match-type
  implementation detail. Under the planning-error fallback, an equivalent
  golden test asserts the structured error and the absence of an executable
  plan.
- The ADR records the rejected mechanisms, measured compile/diagnostic
  evidence, selected mechanism, migration impact, and R1.1 implementation
  estimate.

### R1.2 Output-name uniqueness

Make duplicate projection names and key/aggregate collisions unconstructable
through the typed surface and structured failures through the dynamic surface.

Acceptance criteria:

- Duplicate typed `select` output names do not compile.
- Duplicate typed aggregate names and key/aggregate collisions do not compile.
- Dynamic projection and aggregation return a specific `FrameError` carrying
  every conflicting name.
- Valid wide schemas continue to compile and derive in field order.
- No user-controlled field name reaches `Schema.unsafe` without validated
  uniqueness.

### R1.3 Join identity and names

Remove dependence on user-visible sentinel/mangled names for using-join
planning.

Acceptance criteria:

- A legal field named like the current internal join sentinel neither throws
  nor changes the visible schema.
- Internal join ownership is represented by stable IDs/qualifiers, not a
  collision-prone display name.
- Typed and dynamic using joins produce the same schema and plan.
- Disjoint-name failures are compile-time failures on the typed API and
  structured errors on the dynamic API.

### R1.4 Floating mean

Make the oracle follow its stated sequential IEEE reduction semantics.

Acceptance criteria:

- `mean(+Infinity, 1.0)` is `+Infinity`.
- `mean(-Infinity, 1.0)` is `-Infinity`.
- Opposite infinities and any NaN input produce NaN.
- Empty and all-null groups follow the documented nullable aggregate result.
- Chunk boundaries do not alter the reference result.
- The tests run on JVM and Scala.js.

### R1 exit gate

- All five tests currently marked `.fail` in
  `TypeDisciplineRegressionSuite` are ordinary passing tests; no `.fail`,
  ignore, quarantine, or expected-failure marker remains.
- The maintained `modules` sources contain no `???`, `NotImplementedError`, or
  undocumented public throw path.
- `sbt compileAll testAll` passes warning-clean on JVM and Scala.js.
- The architecture's claims about validated plans, structured errors, and IEEE
  arithmetic are literally true of the public API.

R1 closure receipt (2026-07-26):

- all five former expected failures are ordinary passing tests;
- typed cross-frame expressions and duplicate outputs fail during compilation,
  while dynamic misuse and collisions return structured planning errors;
- using joins carry explicit left/right output mappings without sentinel names;
- sequential IEEE mean cases cover positive and negative infinity, opposite
  infinities, NaN, empty, all-null, and multi-batch input;
- `sbt compileAll testAll` passed on Temurin Java 21.0.11 with core 58/58 on
  both JVM and Scala.js, fs2 13/13 on JVM, and fs2 11/11 on Scala.js.

## R2 — Build the law and measurement court

### R2.1 Reusable generators and laws

Create a cross-built, repository-internal conformance module or testkit. Do not
publish it in `0.1.0` unless an external backend actually needs it.

Required generators:

- unique and deliberately invalid schemas;
- primitive, optional, UTF-8, timestamp, and dictionary columns;
- empty, singleton, wide, nullable, and multi-batch tables;
- minimum/maximum integral values, signed zero, NaN, and infinities;
- expressions distinguished by totality and possible structured failure;
- join cardinalities, null keys, duplicate keys, and skewed group keys;
- CSV/TSV chunks split inside quotes, escaped quotes, CRLF boundaries, and
  multibyte UTF-8 characters.

Required laws:

- typed and dynamic construction agree when both are valid;
- normalization preserves schema, values, ordering guarantee, and the exact
  success/failure boundary;
- results are invariant to legal source batch boundaries;
- storage slices retain and release exactly once;
- dictionary and direct encodings are observationally equivalent;
- every execution backend agrees with the reference interpreter on schema,
  rows, structured errors, and declared ordering;
- unsupported backend capabilities are explicit residuals, never silent
  success or silent fallback.

Acceptance criteria:

- Randomized tests record a reproducible seed on failure and use useful
  shrinkers.
- Boundary and invalid domains are deliberately generated rather than reached
  accidentally.
- Laws target public or deliberately designated backend interfaces, not
  `ReferenceInterpreter` implementation details.
- The same conformance bundle runs on JVM and Scala.js where the capability is
  advertised.
- Discipline is not added merely for appearance; it is used only if public
  typeclass instances or downstream `RuleSet` composition create a real need.

### R2.2 JVM benchmark court

Add a non-published JMH project. Fixture creation, validation, and teardown must
be outside timed regions.

Required workload families:

- primitive and nullable scan;
- UTF-8 and dictionary scan;
- filter alone and fused filter/project with arithmetic;
- low- and high-cardinality grouped count/sum/mean/variance;
- one-to-one, one-to-many, sparse, and skewed joins;
- union and distinct once admitted;
- CSV decode throughput and peak retained memory;
- table construction and bounded row decoding.

Required contestants:

- the semantic reference interpreter;
- each optimized frame4s backend stage;
- Saddle for semantically equivalent JVM operations;
- a specialized-array baseline where it clarifies kernel overhead.

Scautable belongs in a separate ingestion/onboarding comparison, not in
relational backend rankings. That comparison must distinguish Scautable's
compile-time fixed-resource path from frame4s runtime path/stream acquisition
rather than presenting unlike workflows as equivalent.

Acceptance criteria:

- Warmups, forks, measurement iterations, heap/JVM flags, hardware, JDK,
  dependency versions, fixtures, null density, and row counts are recorded.
- Time, throughput, allocation, row-count/checksum, and backend/fallback
  receipts are recorded together.
- Benchmark smoke compilation runs in CI; full measurements run on a
  designated quiet environment or explicit manual release job.
- Raw machine-readable results and a generated Markdown summary are committed
  under a versioned receipt directory.
- Results include every designated workload, including losses.
- Before R5 optimization begins, maintainers commit absolute per-workload
  budgets for throughput/latency and allocation, informed by the measured
  Saddle results and recorded workload sizes. Ratios against both Saddle and
  the best existing frame4s path are included for interpretation.
- At the same budget-ratification point, maintainers designate the fused
  end-to-end architectural-win workload and explain why it exercises plan
  fusion rather than an incidental library mismatch.
- Budgets, the designated win workload, and the regression policy are not
  changed after seeing an optimization result without a written rationale and
  a preserved before/after receipt.

### R2.3 Compile-time and ergonomic court

Track representative positive and negative compilation specimens for narrow,
32-column, and wider practical schemas.

Acceptance criteria:

- Compile-time receipts record clean-build and incremental-build timings on a
  named environment.
- The first diagnostic for a missing, duplicate, or mistyped column includes
  the offending field and actionable context. Foreign-scope use has the
  compile-time diagnostic or structured planning error selected by the R1.1
  ADR.
- Ordinary examples expose named tuples and domain types, not match types,
  variance, or internal evidence parameters.
- A documented first-use script measures the path from an explicit typed CSV
  source to a rendered result, including setup steps and compile/run outcome.
- A release cannot claim improved compile-time or onboarding ergonomics
  without an updated receipt.

R2 closure receipt (2026-07-26):

- the non-published `frame4s-testkit` runs 15 compile-time and randomized law
  specimens on both JVM and Scala.js; failures carry ScalaCheck replay seeds
  and shrink boundary/null/schema witnesses;
- the reusable backend court compares schemas, detached rows, raw floating
  bits, exact structured failures, and declared order, while unsupported
  capabilities are explicit residuals with fallback receipts;
- the non-published JMH 1.37 court records average time, throughput,
  `gc.alloc.rate.norm`, output rows, checksums, environment, backend, and
  fallback status for every designated R2 workload;
- the full versioned receipt includes all reference losses, fair same-checksum
  Saddle comparisons where semantics align, and specialized-array lower
  bounds; Scautable remains a separate fixed-resource/runtime-ingestion
  onboarding comparison;
- absolute workload budgets, best-previous-frame4s admission rules, and the
  fused filter/project architectural-win workload were ratified before R5;
- narrow, 32-column, and 48-column specimens compile, and missing, duplicate,
  mistyped, and foreign-scope diagnostics name user fields without exposing
  match-type/evidence machinery;
- clean, incremental, first-use CSV-to-render, and separate Scala.js execution
  receipts are committed under `docs/benchmarks/receipts`; and
- CI runs the cross-platform laws and compiles the generated JMH harness via
  `benchmarkSmoke`.

## R3 — Make first contact complete without creating a second algebra

### R3.1 Unite source binding and execution

Implement the R0 execution ADR so `FrameSource[F]` is a normal input to
`FrameRuntime`, not a disconnected protocol that users must manually convert
into `ReferenceSources`.

Acceptance criteria:

- Acquiring a source yields or can bind an immutable `Frame[S]` with a stable
  `SourceRef`; constructing that frame performs no I/O.
- One runtime/session/resource can bind multiple typed sources and execute a
  join between them.
- Source inspection validates exact ordered name, type, and nullability before
  a typed frame is authorized.
- `FrameRuntime.stream` and `collect` negotiate source pushdown and report
  accepted and residual capabilities.
- Completion, error, early termination, and cancellation release every source,
  cursor, batch, and materialized table exactly once.
- A query value can be reused for more than one resource acquisition and
  execution.
- Reference fixtures remain available for oracle tests, but users do not need
  `ReferenceSources` for the normal CSV/in-memory workflow.

### R3.2 Typed materialized read view

Add lawful named-tuple row encoding/decoding and case-class interop based on
the same schema descriptor.

Target capabilities, with final names fixed only after an API review:

- construct an owned `Table[S]` from rows;
- decode a row as `S`;
- decode a named column/cell with its exact Scala type;
- convert between `S` and a product type whose `NamedTuple.From` schema is
  exactly equal;
- render a bounded number of rows and the schema.

Acceptance criteria:

- `Option[A]` remains the only typed null representation.
- Out-of-range rows, closed owners, schema mismatch, and scalar decode failure
  are distinct structured errors.
- Decoded immutable rows may safely outlive the table; borrowed column/batch
  views may not and document that fact.
- `fromRows` either returns a completely valid owned table or releases every
  partially allocated buffer.
- Empty tables, all-null optional columns, Unicode, NaN/infinities, and wide
  rows are covered.
- Case-class conversion rejects reordered, missing, extra, or differently
  nullable fields rather than converting by position accidentally.
- Any necessary internal cast is localized, documented, and defended by an
  inductive schema/codec test; no cast appears in the user API.
- `Table` gains no transformation method that competes with `Frame`.

### R3.3 Bounded rendering

Acceptance criteria:

- `show` is deterministic, bounded by explicit row and width limits, and never
  scans more rows than requested.
- Null, empty string, NaN, infinities, timestamps, Unicode, and truncated cells
  have specified rendering.
- Rendering a closed table returns a structured error.
- Rendering does not transfer or obscure ownership.
- Golden output tests run on JVM and Scala.js.

### R3.4 Incremental CSV/TSV and path acquisition

Acceptance criteria:

- Portable ingestion accepts an FS2 byte or character stream and emits bounded
  record batches without materializing the entire input.
- JVM convenience accepts an fs2 `Path` or equivalent scoped path abstraction;
  Scala.js retains portable stream/string entry points without pretending to
  have JVM filesystem support.
- Headers are validated exactly against the explicit typed schema before
  execution.
- Quoted delimiters/newlines, escaped quotes, CRLF, empty terminal fields,
  configurable null tokens, malformed rows, and multibyte characters split
  across chunks are tested.
- Decode errors carry logical row, column, source value, and expected type.
- Peak retained input/storage is bounded by configured batch size plus the
  largest in-progress record, with a stress receipt.
- Early stream termination and cancellation close the input resource and all
  decoded batches.
- Existing string-in/string-out APIs either remain as clearly bounded
  conveniences or receive a documented migration path.

### R3 exit gate

A fresh consumer project must compile and run a short example that:

1. declares a readable named-tuple row type;
2. acquires a typed CSV path/resource;
3. builds a pure filter/project query;
4. collects or streams it through the public runtime;
5. prints a bounded table;
6. closes everything through one visible `Resource` scope.

The example must use no `.toOption.get`, internal package member,
`ReferenceSources`, manual batch construction, unsafe cast, or handwritten
runtime `Schema`.

R3 closure receipt (2026-07-26):

- immutable `SourceBinding[F, S]` descriptions expose a pure `Frame[S]`, while
  `FrameRuntime.resource` acquires, inspects, and exactly validates one or more
  sources in an invocation scope; a two-source typed join executes without
  user-managed `ReferenceSources`;
- `streamWithReceipt` and `collectWithReceipt` report accepted and residual
  pushdown, and the runtime tests cover reusable acquisition, schema mismatch,
  completion, failure, early termination, cancellation, cursor/batch leases,
  and exactly-once source finalization;
- `Table[S]` remains a read view and now supports fully owned `fromRows`,
  detached row/cell/column decoding, exact `NamedTuple.From` product
  round-trips, bounded row/schema rendering, and distinct structured
  closed/range/schema/scalar/storage failures;
- the sole named-tuple representation cast is isolated in
  `NamedTupleRepresentation`, documented against Scala 3.7's tuple erasure
  contract, absent from every user signature, and exercised inductively through
  32 fields on both JVM and Scala.js;
- incremental CSV/TSV byte and character sources handle arbitrary UTF-8 chunk
  splits, quoted delimiters/newlines, escaped quotes, CRLF, terminal fields,
  null tokens, malformed widths, logical decode locations, and cancellation;
  the JVM path adapter is backed by FS2 file resources while Scala.js retains
  only portable entry points;
- the versioned
  `docs/benchmarks/receipts/2026-07-26-r3-first-contact` receipt identifies the
  source state, runs the external-package consumer, and records a lazy
  10,000-row/17-row-batch stress law; and
- the exact `compileAll testAll benchmarkSmoke` gate passes with 70 core tests
  on each platform, 15 testkit laws on each platform, 24 fs2 JVM tests, 20 fs2
  Scala.js tests, the downstream first-contact compile, and the JMH harness
  compile.

## R4 — Complete the small algebra

Every item below gets compile-time schema tests, dynamic structured-error
tests, oracle examples, randomized laws, explain/order tests, and JVM/Scala.js
coverage.

### R4.1 Projection conveniences

- `rename` lowers to `Project` and rejects collisions.
- `drop` lowers to `Project` and rejects missing names.
- `replace` lowers to `Project`, requires an existing name, and computes the
  replacement type/nullability.

Acceptance criteria:

- No new logical node is introduced.
- The resulting named-tuple schema is exact and ordered.
- Multiple-column variants do not accept duplicate requests.
- Explain output reveals the project rather than a second operation algebra.

### R4.2 Distinct

Before implementation, amend the constitution to define grouping/distinct
equivalence for null, NaN, signed zero, timestamps, UTF-8, and dictionary
values.

The current typed `AggregateSelection[EmptyTuple]`, `GroupedFrame.aggregate`,
and reference aggregate cursor already admit key-only grouping. Protect that
capability with a direct regression test before implementing the lowering; if
the test exposes a hidden node or schema change, reclassify `distinct` as a
foundational extension rather than weakening the admission rule.

Acceptance criteria:

- `distinct` lowers to grouping by all fields and preserves the input schema.
- Key-only grouping with `EmptyTuple` aggregates is directly tested through
  the public typed and dynamic paths.
- Nulls coalesce according to grouping semantics.
- Dictionary identity does not affect logical distinctness.
- The result order is explicitly unspecified.
- The lowering agrees with an independent small-row set oracle.

### R4.3 Right join

Acceptance criteria:

- Right join lowers through the existing left-join semantics plus projection.
- The public output preserves the original left-then-right field order, with
  original-left fields nullable as required.
- Duplicate matches and null predicates mirror left-join behavior under input
  swapping.
- The lowering introduces no `RightOuter` node unless benchmark or optimizer
  evidence later proves that a native node is necessary.

### R4.4 `unionAll`

`unionAll` is a foundational plan node, not a convenience theorem.

Before implementation, amend the constitution with exact schema compatibility,
duplicate preservation, branch evaluation/failure order, order propagation,
streaming, and ownership semantics. Add `UnionAll` to logical and physical
explain and to the exhaustive plan documentation.

Acceptance criteria:

- Typed inputs require the exact same ordered schema; dynamic mismatch returns
  ordered binding issues.
- Rows and duplicates are preserved.
- The order guarantee states precisely whether stable inputs concatenate
  stably; unspecified inputs remain unspecified.
- Streaming execution does not require both inputs to be materialized.
- Normalization does not reorder error-producing branches.
- Explain identifies both children, the output schema, order guarantee, and
  selected backend/fallback.

Runtime realization (2026-08-02): the public `SourceBinding` stream route now
concatenates union branches lazily. A cross-platform pull-count test proves
that a limit satisfied by the left branch does not pull the right branch; batch
and source finalizers are asserted after completion.

### R4.5 Semi and anti joins

Treat semi and anti as foundational `JoinKind` extensions. Before
implementation, amend the constitution with existential truth semantics,
duplicate behavior, null-predicate behavior, left-row order guarantees, and
backend capability/fallback behavior. Logical and physical explain must name
the exact join kind.

Acceptance criteria:

- Both are explicit join kinds with the left schema as output.
- A left row appears once for semi join when any predicate result is true,
  regardless of the number of matching right rows.
- A left row appears once for anti join when no predicate result is true;
  false and null do not count as matches.
- Empty inputs, duplicate matches, null keys, non-equi predicates, and
  cancellation are covered.
- The implementation does not simulate existence through user-visible
  sentinels, join-plus-distinct, or accidental null checks.
- Explain and order tests distinguish semi/anti from inner/left joins and match
  the amended constitution.

### R4.6 Floating square root and population standard deviation

Treat `sqrt` as a foundational expression extension. Before implementation,
amend the constitution and expression explain contract with its type,
nullability, IEEE, totality, and normalization behavior.

Acceptance criteria:

- `sqrt` is explicit, floating-only, null-preserving, and follows documented
  IEEE behavior for negative values, signed zero, NaN, and infinities.
- The existing population-variance operation is exposed under the unambiguous
  name `variancePop` before the first compatibility baseline; a shorter
  `variance` alias exists only if it cannot be mistaken for sample variance.
- `stddevPop` is defined as the population variance result followed by `sqrt`
  and reuses the existing empty/all-null aggregate policy.
- Sample standard deviation is not silently conflated with population standard
  deviation.
- Stable analytic examples and chunk-boundary properties pass on JVM and
  Scala.js.

### R4 exit gate

- The public operation matrix and semantics are documented in one discoverable
  guide.
- Every convenience explains as existing nodes; every foundational extension
  is named in the closed-plan documentation.
- The plan remains exhaustive under fatal warnings.
- No materialized transformation duplicate exists on `Table`.

R4 closure receipt (2026-07-26):

- rename, drop, replacement, distinct, right join, exact-schema `unionAll`,
  left-semi/left-anti joins, floating `sqrt`, `variancePop`, and `stddevPop`
  are available through the intended typed and dynamic surfaces; convenience
  operations lower to existing nodes and foundational extensions are explicit
  in logical and physical explain;
- the constitution now defines grouping/distinct logical-key equivalence,
  ordered and lazy union branch semantics, existential join truth and
  short-circuit behavior, and floating/population-statistic semantics;
- `SmallAlgebraSuite` covers exact schemas and diagnostics, an independent
  distinct oracle including physical-encoding adversaries, swapped right-join
  duplicate/null behavior, union failure order, semi/anti empty/non-equi and
  error-short-circuit behavior, and stable numeric examples;
- the reusable ScalaCheck court adds independent randomized distinct,
  union-order, existential-join, and batch-boundary laws, while the FS2 court
  verifies union/semi/anti cancellation returns every execution lease;
- [the public operation map](operations.md) is the single discoverable surface,
  lowering, order, and edge-case guide, and `Table` still has no transformation
  algebra;
- the versioned
  `docs/benchmarks/receipts/2026-07-26-r4-small-algebra` receipt identifies the
  exact source state, full gate output, timing, environment, and
  constraint-escape audit; and
- `compileAll testAll benchmarkSmoke` passes on JVM and Scala.js with 89 core
  tests per platform, 19 testkit tests per platform, 25 FS2 JVM tests, 21 FS2
  Scala.js tests, the downstream first-contact compile, and the JMH harness.

## R5 — Claim-gated performance track

R5 does not block the factual `0.1.0` release. It gates inclusion of an
optimized backend and every comparative speed claim. R5a may begin after R2
for the existing scan, filter/project, aggregation, and inner/left-join
algebra. R5b depends on both R2 and the corresponding R4 operations.

### Architecture

The optimized backend must be physically and conceptually separate from
`ReferenceInterpreter`. It may use localized mutable builders, specialized
arrays, and audited internal unsafe operations behind validated boundaries.
It may not change public schemas, failures, ownership, or semantics.

Implement in benchmark-evidence order:

1. specialized scan and projection kernels;
2. fused filter/project;
3. hash aggregation;
4. hash inner/left join;
5. after R4, hash semi/anti join and union/distinct execution.

The order may change only when the committed R2 receipt shows a better
impact/complexity sequence.

### Per-kernel acceptance criteria

- A backend conformance law compares schema, values, nulls, structured errors,
  and declared order with the reference interpreter.
- Adversarial inputs include empty batches, all-null columns, NaN/infinities,
  integral overflow, dictionary values, skew, and batch-boundary changes.
- `physicalExplain` names the backend, specialized/fused operators, accepted
  pushdowns, and every fallback.
- Unsupported input is either a declared fallback with a receipt or a
  structured capability refusal.
- The primary benchmark is compared with the best previous frame4s
  implementation of the same completed work. That is the reference path only
  for the first optimized implementation; later kernels must beat the current
  optimized path rather than a permanently slow strawman.
- The primary benchmark's lower confidence bound shows at least a 20%
  throughput improvement or a 25% allocation reduction over that best previous
  path and satisfies the absolute R2 workload budget, without a material
  regression in other workloads handled by the same kernel. Otherwise the
  added complexity does not land.
- Every owned allocation is released under success, error, early termination,
  and cancellation.

### Optimized-backend and comparative-claim criteria

- Scan, filter/project, group, and join all have comparative JMH results; none
  is omitted because frame4s loses.
- The optimized backend satisfies every ratified absolute R2 budget and
  materially improves each family over the best frame4s path that preceded it.
- Saddle comparisons perform equivalent completed work and disclose semantic
  or representation differences.
- The fused end-to-end workload designated during R2 budget ratification is
  reported. Failure to win is published honestly and blocks a comparative
  “faster than Saddle” claim, not the factual `0.1.0` release.
- Scala.js has a reproducible workload receipt and no unqualified JVM result is
  generalized to JavaScript.
- No benchmark result is a required noisy PR timing gate; CI verifies benchmark
  compilation and bounded smoke correctness, while release measurements run in
  the designated environment.
- If these criteria are incomplete, R5 remains unpublished or experimental and
  the `0.1.0` artifacts/documentation describe only the semantic reference
  backend.

## E0 — Close the ergonomic public API gap before the API freeze

R1 through R4 established the sound typed algebra, semantic oracle, execution
boundary, materialized read view, documentation machinery, and small relational
surface. E0 removes proof bookkeeping from routine use without changing any of
those contracts. It is a release gate because `0.1.0` establishes the first
public compatibility baseline.

Mote issue `bd-01KYG47EQY15E3XQB9EWDZ3WXG` is the governing epic. Its children
E1 through E5 are the implementation and certification sequence. E2 and E3 may
proceed in parallel only after E1 closes. E4 depends on both implementations.
E5 certifies the combined public API and blocks R6.

### Governing rule

Remove syntax only when frame4s already possesses the information. Keep syntax
when the caller chooses schema, nullability, conversion, ownership, an output
name for a computation, a multi-source identity, backend selection, or receipt
behavior.

Every convenience must have one tested expansion into the existing public
primitives. The concise and expanded forms must agree on types, logical plans,
schemas, explain output, values, structured failures, ordering, pushdown,
effects, finalization, and material execution work.

The following remain prohibited:

- an implicit conversion from Scala values to expressions;
- numeric widening or hidden casts;
- a `Dataset`, `FrameInput`, eager `Table`, or source-bound transformation
  dialect;
- transformation methods on `SourceBinding`;
- hidden ownership for an escaping `Table`, batch, cursor, or stream;
- random, content-derived, object-identity, or magic-string source identity;
- user-facing diagnostics led by match types, origin unions, helper builders,
  or internal evidence.

### Effort and dependency plan

| Work | Size | Depends on | Release result |
| --- | --- | --- | --- |
| E1 contract and complete specimen court | S | R1–R4 | Ratified signatures, diagnostics, and ownership boundaries |
| E2 expression and projection implementation | M | E1 | Direct exact operands, raw-column name preservation, and closed direct-null hole |
| E3 single-source execution facade | M | E1 | Concise acquisition and execution with unchanged `Frame` algebra |
| E4 first-contact and guide rewrite | S–M | E2 and E3 | Documentation teaches only the landed progressive API |
| E5 combined release certification | M | E4 | Clean supported-toolchain receipt and R6 API-freeze approval |

### E1 — Ratify the complete contract

E1 is not complete merely because a signature experiment compiles or because
the same experiment passes on JDK 21. Close it only when the decision record,
downstream specimens, diagnostics, and supported-toolchain receipt cover the
whole matrix below.

Required positive specimens:

1. typed in-memory rows through exact scalar filtering, raw-column projection,
   and detached bounded rendering;
2. typed CSV with an optional field through nullable filtering, grouping or
   aggregation, and scoped collection;
3. one named `Frame` transformation applied independently to two same-schema
   bindings;
4. a query value conditionally extended and executed more than once;
5. an explicit two-source join with distinct stable identities and a receipt;
6. the proposed no-reference in-memory, CSV, TSV, and JVM path constructor
   signatures, including the transition to explicit identities for
   multi-source execution.

Required negative specimens:

- unknown fields;
- wrong exact scalar types and attempted numeric widening;
- duplicate raw or explicitly named outputs;
- an unnamed computed projection;
- foreign-frame expression reuse;
- invalid nullable predicates and operations;
- join output-name collisions;
- direct raw `null` through every proposed operand family and literal entry
  point that the API claims to reject.

E1 must state the attainable null guarantee under Scala's default nullability.
Direct `null` syntax must not resolve to `LiteralExpr` or reach a dereference.
The ADR must separately decide how explicit type arguments or a value already
typed as a non-null reference are treated; it must not imply that a downstream
compiler without explicit-nulls can prove more than Scala's type system
provides.

E1 acceptance criteria:

- every proposed signature appears in a compiling downstream-package specimen;
- every convenience has a written canonical expansion and ownership boundary;
- the first diagnostic for every ordinary invalid call names the caller's
  field, value, computation, scope, or identity mistake;
- no prototype uses a cast, broad conversion, Boolean identity flag, sentinel,
  global runtime, or second algebra;
- the positive and negative matrices pass under Scala 3.7.4, sbt 1.10.5, and
  the supported Temurin JDK 21;
- the receipt records line and concept counts only as observations and does not
  use them to waive a safety or diagnostic failure.

### E2 — Implement expression and projection conveniences

Close the known direct-null hole before admitting any new convenience. Then add
the selected exact operand and raw-column projection forms.

Implementation requirements:

- implement the ratified null contract at every affected expression and
  literal boundary;
- add exact-`A` equality, inequality, null-safe equality, ordering, arithmetic,
  and division overloads that construct `Expr.literal(value)` and call the
  existing expression-to-expression operation;
- retain `LiteralExpr[A]` for named or reusable literals;
- make checked `Scope.col` return a private-constructor
  `ColumnExprOf[Name, A, Origin]` that retains the singleton field name and
  provenance;
- allow projection and group-key selection to consume raw `ColumnExprOf` values
  or explicitly named expressions;
- continue requiring `.as("name")` after arithmetic, functions, aggregates, or
  any other computation that does not already possess an output name.

E2 acceptance criteria:

- concise and expanded calls produce identical resolved expressions, plans,
  schemas, explain output, oracle results, structured failures, and ordering;
- direct raw `null` fails at compile time with the ratified `Option` guidance
  rather than compiling to a later exception;
- wrong scalar types and widening attempts fail with the expected and found
  Scala types before internal evidence appears;
- duplicate raw-column projections, missing fields, unnamed computations, and
  foreign-frame columns fail at compile time with actionable diagnostics;
- an inferred projected query retains a concrete named-tuple output type across
  a downstream artifact boundary, so typed table decoding needs no redundant
  result annotation;
- no implicit `Conversion`, cast, new expression node, or runtime lookup of a
  type-level field name is introduced;
- narrow, 32-column, and 48-column specimens pass on JVM and Scala.js within
  the ratified compile-time budgets.

### E3 — Implement the single-source execution facade

Add acquisition and execution conveniences to `SourceBinding`; do not add
query transformations to it.

Required public forms:

```scala
binding.collect(query)         // Resource[F, Table[O]]
binding.stream(query)          // Stream[F, RecordBatch]
binding.render(query, options) // F[String]
```

Their only canonical expansions are
`FrameRuntime.resource(binding).flatMap(_.collect(query))`,
`Stream.resource(FrameRuntime.resource(binding)).flatMap(_.stream(query))`, and
scoped collection followed by `Table.show` for the detached string.

No-reference constructors use a private `BindingIdentity` ADT with `Explicit`
and `SingleSource` cases. A runtime containing more than one binding must reject
any `SingleSource` identity with a dedicated `RuntimeBindingError` that directs
the caller to the explicit-`SourceRef` constructors. Code must not recover this
state by inspecting a reserved string or Boolean.

E3 acceptance criteria:

- `collect` keeps `Table` ownership in `Resource`, `stream` keeps runtime and
  batch ownership in `Stream`, and only detached `render` brackets internally;
- the same immutable query value can be named, passed, conditionally composed,
  reused across acquisitions, and executed through concise or explicit forms;
- concise and explicit forms have identical source inspection, schema
  validation, pushdown, plans, values, errors, ordering, receipts where
  applicable, and finalization;
- success, acquisition failure, decode failure, render failure, early
  termination, and cancellation close every owner exactly once;
- explicit multi-source construction, backend selection, and receipt-bearing
  execution remain unchanged;
- `SourceBinding` exposes no filter, projection, grouping, aggregation, join,
  sort, union, distinct, or expression method;
- portable behavior passes on JVM and Scala.js, and path acquisition remains a
  JVM-only scoped convenience.

### E4 — Rewrite first contact after E2 and E3 land

Do not publish hypothetical syntax. Rewrite documentation only against the
merged public API.

Documentation order:

1. README first screen: named-tuple schema, in-memory rows, reusable `Frame`
   query, detached bounded rendering, and the result;
2. quick start: the same program with explanation of schema, query purity, and
   execution;
3. CSV/TSV guide: replace only source construction and retain the same query;
4. ownership guide: show `Resource[F, Table[S]]`, streaming, explicit runtime,
   multi-source identity, backend selection, and receipts;
5. API Scaladoc: document exact operands, retained raw-column names, computed
   names, nullability, identity, and ownership at the relevant public types.

E4 acceptance criteria:

- the simple detached examples contain no `SourceRef`, `FrameRuntime`,
  `Resource` import, manual `Either` lifting, redundant unchanged-column
  aliases, internal API, partial `.get`, or unsafe cast;
- owned and multi-source examples retain every meaningful scope and identity;
- README, mdoc guides, Scaladoc, first-contact program, and staged consumers use
  the same terminology and progression;
- every example compiles, executable examples run, and expected output is
  recorded;
- the guide states that the `0.1.0` runtime is reference-only unless a public
  optimized backend is separately admitted.

### E5 — Certify the combined public API

Run the complete E0 court from a clean checkout of the exact candidate commit.
Local dirty-worktree or unsupported-JDK runs may be retained as development
evidence but cannot close E5.

Required receipts:

- positive downstream-program matrix;
- negative compilation and first-diagnostic matrix;
- concise-versus-expanded semantic and plan parity;
- ownership and exact-once lifecycle outcomes;
- clean and incremental narrow/32/48-column compilation;
- mdoc/Laika, Scaladoc, staged JVM, and staged Scala.js consumers;
- complete release command and sanitized environment/source hashes.

E5 acceptance criteria:

- every E0 positive and negative case passes using only public API;
- `sbt formatCheck docsCheck apiDocs versionPolicyCheck compileAll testAll
  benchmarkSmoke` passes on the supported toolchain;
- the release rehearsal and staged consumers pass from the same clean commit;
- no receipt or artifact contains an absolute local path, secret, snapshot
  dependency, `.mote`, `vendor`, or `target` payload;
- public docs and artifacts contain no prototype package or uncommitted syntax;
- the released `FrameRuntime` is described as reference-only unless a public
  optimized backend has independently passed R5 packaging, conformance,
  explain, lifecycle, and performance admission;
- E0 closes before R6 freezes the public compatibility surface.

### E0 release exit criteria

- There is one `Frame` transformation algebra from the shortest example through
  explicit multi-source and receipt-bearing execution.
- Invalid ordinary programs remain unconstructable at the intended boundary,
  including the ratified direct-null cases.
- Valid ordinary one-source programs do not restate evidence frame4s already
  possesses.
- Owned values keep visible scopes; detached values may outlive them.
- Every convenience is a tested expansion rather than a second semantics.
- The user-facing guides teach landed behavior, and the full clean JDK 21 court
  proves it.

### Performance posture after E0

E0 does not route ordinary execution to the package-internal columnar
candidate. `0.1.0` may remain reference-only and publish no comparative runtime
claim. A later public optimized backend is a separate R5 admission decision and
must expose backend selection, explain and fallback behavior, conformance,
ownership, and supported-platform limits without changing the E0 query API.

## R6 — Freeze the API and make the repository releasable

### R6.1 Compiler, formatting, and CI

Acceptance criteria:

- E0 is closed and the ergonomic public API has passed its combined downstream
  and diagnostic court before the compatibility freeze.
- `-deprecation`, `-feature`, `-unchecked`, existing unused/value-discard
  checks, and `-Werror` remain enabled.
- Deterministic formatting is configured and checked without broad
  suppression.
- Required CI jobs distinguish:
  - core JVM tests;
  - core Scala.js tests;
  - FS2 JVM tests;
  - FS2 Scala.js tests;
  - laws/backend conformance;
  - formatting and documentation;
  - staged artifact/consumer smoke;
  - benchmark compile/smoke.
- Node, JDK, sbt, and relevant actions/plugins are pinned to supported ranges.
- The normal court stays on repository-pinned sbt 1.10.5. If publication needs
  a newer sbt capability, the release-only runner is separately pinned,
  exercised, and documented rather than silently broadening the support claim.
- A staged JVM consumer compiles and runs against the candidate artifacts.
- A staged Scala.js consumer links and runs under Node against the candidate
  artifacts.
- A JVM coverage report is produced as diagnostic release evidence and reviewed
  for untested public ADT branches and ownership/error paths; no arbitrary line
  percentage substitutes for the R2 laws.
- Remote CI configuration is not called “green” until the required jobs have
  actually passed on the release commit.

### R6.2 Compatibility

Acceptance criteria:

- `0.x` source and semantic compatibility policy is explicit.
- Public API changes since the preceding release candidate are reviewed and
  listed.
- The staged consumer suite protects the documented typed and dynamic entry
  points.
- Binary compatibility tooling is configured to use `0.1.0` as the baseline
  immediately after publication; no pre-release baseline is invented.
- Serialized plans remain explicitly non-public.

### R6.3 Documentation and examples

Required guides:

- three-minute typed in-memory quick start;
- typed CSV/TSV path-to-query-to-show workflow;
- schemas, nullability, expressions, and compile errors;
- joins, grouping, sorting, union, and distinct;
- dynamic schema inspection and exact typed binding;
- execution, streaming, collection, and ownership;
- semantic constitution;
- logical and physical explain;
- backend selection and capability/fallback receipts when an optional backend
  ships, plus the performance method in all cases;
- compatibility, migration, security, and contribution policy.

Acceptance criteria:

- Every code example is compiled in CI through mdoc or an equivalent executable
  documentation mechanism.
- Quick starts contain no internal APIs, manual buffers, unsafe casts, hidden
  global runtime, partial `.get`, or redundant proof bookkeeping that E0
  committed to derive.
- Examples that return an owned table or stream keep `Resource` or `Stream`
  visible; only detached results may hide the completed ownership scope.
- API Scaladoc is warning-clean and published with the release.
- The README describes only capabilities present in the candidate artifacts.
- Benchmark pages link raw receipts and state workload/environment limitations.
- Error-message examples are asserted by compile tests rather than transcribed
  from memory.

### R6.4 Publication, provenance, and security

Acceptance criteria:

- `frame4s-core` and `frame4s-fs2`, plus any ratified backend artifact, have
  complete POM metadata and no snapshot dependencies.
- A local or private-repository publication rehearsal verifies JVM and
  Scala.js coordinates, sources, documentation jars, signatures, and checksums.
- The configured production route targets the current Central Publisher
  Portal, has an explicit stable-artifact staging probe, and does not depend on
  the retired legacy OSSRH service.
- Release credentials/signing satisfy the continuity policy chosen in R0:
  either two authorized maintainers exercise the path, or the explicitly
  amended single-maintainer policy has a tested offline recovery/break-glass
  procedure and discloses the remaining bus-factor risk.
- Vendored audit material is excluded from published source/artifact payloads.
- Dependency and copied-source licenses are reviewed and the provenance record
  is current.
- GitHub private vulnerability reporting or another private reporting channel
  is enabled; `SECURITY.md` names supported versions and response expectations.
- Release notes enumerate semantics, known limitations, compatibility policy,
  benchmark scope, and migration instructions.
- The repository contains no secret, local absolute path, or machine-specific
  credential/configuration in staged artifacts or receipts.
- The owner-only recovery preflight is executable, fails closed on a dirty
  checkout or missing evidence, and emits only non-secret statuses, the
  candidate commit, and the public signing fingerprint.

## R7 — Release candidate and publication

### Candidate gate

Create the release candidate from a clean checkout of the exact candidate
commit. The candidate is acceptable only when all of the following are true:

- [ ] Every R1 constitutional regression is an ordinary passing test.
- [ ] The R2 laws, generators, benchmark smoke, and compile/ergonomic court pass.
- [ ] The normal source-to-query execution path and first-use consumer pass.
- [ ] Every committed R4 operation satisfies its typed, dynamic, oracle, and
      cross-platform criteria.
- [ ] E0 is closed: direct-null regressions, concise/expanded parity,
      ownership, diagnostics, documentation, and downstream programs pass on
      the frozen public API.
- [ ] If an optimized path is included, it satisfies the ratified R5
      conformance/performance criteria; otherwise the artifacts and docs are
      explicitly reference-only and make no comparative speed claim.
- [ ] `sbt compileAll testAll` passes on the supported clean environment.
- [ ] Formatting, documentation, staged JVM consumer, and staged Scala.js
      consumer gates pass.
- [ ] Full release JMH and Scala.js receipts are attached to the candidate
      commit and include losses.
- [ ] Buffer/resource counters return to zero after every release workload.
- [ ] The API, semantics, ownership, compatibility, and deferred-scope docs
      match the artifacts.
- [ ] Provenance, dependency licenses, private security reporting, and
      maintainer release access are verified.
- [ ] The version contains no `SNAPSHOT`; the tag, artifacts, checksums, and
      release notes all name the same commit and version.

### Publication acceptance

- Signed artifacts for every promised JVM and Scala.js coordinate are visible
  in the public repository.
- A clean external consumer resolves the public coordinates and repeats the
  documented quick start.
- Published Scaladoc and release notes resolve without private/local links.
- The GitHub release identifies the exact commit, artifacts, checksums,
  semantic receipt, benchmark receipt, and known limitations.
- The post-release compatibility baseline is recorded for the next change.
- If publication verification fails, no broader announcement or performance
  claim is made until a corrected version is available.

## Assurance baseline and required exit state

| Assurance dimension | Rating at ratification | Evidence/gap at ratification | `0.1.0` exit state |
| --- | --- | --- | --- |
| ScalaCheck use and generator quality | Missing | MUnit examples exist; no property dependency, generators, shrinkers, or seed policy | Meaningful cross-built generators and reproducible properties from R2 |
| Reusable law-test module | Missing | Semantic tests are tied to current suites | Shared normalization, storage, and backend conformance laws |
| Test framework and Discipline integration | Present but incomplete | MUnit runs cross-platform; no property bridge; Discipline has no current typeclass need | MUnit/property integration executable; Discipline explicitly unnecessary unless the API changes |
| Typeclass lawfulness and coherence | Not applicable | No public algebraic typeclass instance family currently defines dataframe semantics | Reassess only if public instances are added |
| Backend/provider conformance | Missing | Only the reference interpreter exists; source pushdown has example tests but no reusable backend court | The R2 conformance bundle is executable; every optional backend/capability included in `0.1.0` runs it |
| Cross-platform and cross-version CI | Present but incomplete | One CI job runs JVM and Scala.js on JDK 21; no staged consumers or explicit supported-range probes | Required JVM/JS, staged-consumer, and supported-toolchain gates |
| Numerical and computational assurance | Present but incomplete | Strong targeted examples exist; one documented mean semantic failure remains | Constitution cases plus randomized, analytic, and adversarial laws |
| Differential and independent oracles | Missing | The reference interpreter is an internal semantic oracle but no backend differential harness exists | Backend-vs-reference laws plus independent small-result oracles where useful |
| Failure, convergence, and resource contracts | Present but incomplete | Structured ADTs and cancellation/close tests exist; public construction holes and disconnected source execution remain | Every public path and terminal outcome has typed/structured failure and exact release tests |
| Work and allocation accounting | Present but incomplete | Buffer tracker and smoke receipts exist; no comparative allocation court | JMH allocation results, checksums/work receipts, and zero-owner terminal checks |
| Compiler discipline | Strong | Fatal deprecation/feature/unchecked, unused, value-discard, and non-unit checks | Preserve without broad suppression |
| Formatting and semantic rewrites | Missing | No configured formatting or rewrite gate | Deterministic format check; semantic rewrite tooling only if it solves a named problem |
| Binary and source compatibility | Present but incomplete | `early-semver` is declared; no released baseline or enforcement | Explicit `0.x` policy, staged consumers, and post-release baseline |
| Coverage and mutation signal | Missing | No coverage or mutation configuration | Risk-oriented JVM coverage report; no arbitrary percentage substitutes for laws; mutation remains optional |
| Benchmark and performance evidence | Present but incomplete | Storage/relational smoke receipts explicitly disclaim stable claims | JMH court, Scala.js receipt, raw results, fixed policy, and honest comparisons |
| Documentation and release evidence | Present but incomplete | Architecture/governance docs and examples exist; no executable docs, API site, publication, or security channel | Executable guides, Scaladoc, release rehearsal, public artifacts, security and provenance evidence |

## Slice-level definition of done

Every implementation slice must satisfy the relevant subset below before it is
merged:

- the public behavior and non-goals are stated;
- typed and dynamic behavior agree where both exist;
- invalid typed use has a compile-time test with an actionable diagnostic;
- dynamic and execution failures use a structured ADT;
- portable behavior is tested in `shared` on JVM and Scala.js;
- new semantics amend the constitution and reference oracle;
- new rewrites prove value- and error-preservation;
- new backend behavior runs conformance laws and has `physicalExplain` evidence;
- new performance complexity has a predeclared workload and receipt;
- new ownership paths test success, error, early termination, and cancellation;
- documentation examples compile;
- no unrelated dependency or public surface is added;
- `sbt compileAll testAll` and the slice's additional gates pass.

## Stop-the-line rules

- Do not replace a compile-time invariant with a runtime check merely to
  preserve a pre-release signature.
- Do not hide a capability gap behind silent fallback or a misleading
  `streaming` label.
- Do not land an optimized kernel that fails oracle parity or does not earn its
  complexity under the ratified performance policy.
- Do not admit a new plan concept under the label of convenience.
- Do not let a materialized operation create a second transformation algebra.
- Do not close E1 on a supported-JDK rerun while any required positive,
  negative, constructor, or ownership specimen is missing.
- Do not publish a public operand API through which direct raw `null` resolves
  as a reusable literal expression or fails later by dereference.
- Do not document a concise form until that exact public form is merged and
  executable in the documentation court.
- Do not call configured CI, publication, security, or benchmark machinery
  verified until it has run successfully on the candidate.
- Do not publish while an expected-failure marker documents a known
  constitutional contradiction.

## Immediate first tranche

The first implementation tranche should contain only:

1. the timeboxed expression-provenance spike and ADR;
2. projection/aggregation output-name uniqueness;
3. join internal identity;
4. IEEE mean repair;
5. the selected expression-provenance implementation;
6. removal of every expected-failure marker;
7. the execution-boundary ADR;
8. the initial property generators and benchmark-project skeleton.

Items 2–4 and the R2 skeletons proceed while the provenance spike runs; they do
not wait behind an unexplored type-system design. The selected provenance
implementation remains the R1 exit gate, but the spike produces an explicit
fallback and re-estimate instead of allowing the whole program to hang on an
unbounded experiment.

## Current release tranche

E1's full specimen and first-diagnostic matrix, E2's expression conveniences,
E3's single-source execution facade, and E4's first-contact/documentation
rewrite pass on the development JDK. Their receipts remain provisional because
they were produced from a dirty JDK 22 checkout.

The remaining public-API critical path is:

1. review and land E1-E4 as a coherent public-API change;
2. run E5 from the clean exact candidate on Temurin JDK 21, including the full
   release command and staged consumers;
3. close E0 only if that supported-toolchain court passes;
4. freeze the API in R6 and produce the R7 candidate.

The package-internal index/layout work remains a separate reviewable change and
does not alter the E0 public API.
