# ADR 0005: route `collect` through the columnar engine

Status: accepted, 2026-07-30.

## Context

`FrameRuntime` had two execution entry points, `stream` and `collect`, and both
reached `ReferenceInterpreter` through one private method. `ColumnarInterpreter`
had no reference anywhere outside tests and benchmarks.

Every comparative measurement in `docs/benchmarks/receipts/` therefore described
an engine the public API never ran. The gap was not academic: the reference
join is a nested-loop cross product costing 39.5 ms and 237 MB per operation on
a 1,000-row fixture, and it cannot execute a 1,000,000-row join at all.

The court's existing position is that the reference interpreter defines
semantics. That does not require it to be the only thing that runs, only that
anything else agrees with it, which the reusable conformance boundary in
`frame4s-testkit` already enforces.

## Decision

`collect` runs the columnar engine and falls back to the reference interpreter
whenever that engine declines.

`stream` keeps running the reference cursor. This is the part worth stating
plainly, because promoting both would have been the simpler change and looked
like a bigger win. The columnar engine materializes its entire result before
yielding anything. Promoting it under `stream` would convert an incremental
stream into materialize-then-emit while the type signature, the documentation,
and the tests all stayed green. Callers streaming a large result to bound memory
would silently lose that property. `collect` already materializes by contract,
so it gives up nothing.

`ColumnarInterpreter.collectBatches` returns a `Left` for any plan the engine
will not answer: no kernel matched, a kernel hit a capability residual,
execution errored, or the result holds a vector with no batch form. A `Left` is
a decline, never a failed query. The reference path then runs and remains the
definition of correct behaviour, so declining is always safe and the reason is
meant to be reported rather than swallowed.

## Materialization bridge

The engine computes into `ColumnarVector`; the public API returns `RecordBatch`
of `ColumnArray`. The bridge has a typed fast path for materialized value
vectors and a generic path that rebuilds a column from its scalars.

The generic path is not redundant. Some vectors are views rather than storage --
a filter yields a `SelectedVector` holding an input plus a selection vector --
so there is no array to hand over. It is driven by the schema's declared
`DataType` rather than by inspecting values, because an all-null column carries
no evidence of its own type.

`RecordBatch.apply` re-validates width, per-column length, declared type, and
nullability against the schema, so a bridge defect surfaces as a structured
storage error instead of a malformed table.

## Consequences

Callers of `collect` get the optimized engine without changing any code, and
without any change to results: the ratified 1,000-row checksums are unchanged.

A silent-decline regression is the failure mode to guard against, because it
produces a passing test suite that proves nothing -- both sides of a
reference-versus-columnar comparison become the reference interpreter. The
conformance test in `ColumnarInterpreterSuite` therefore fails explicitly when
the engine declines a plan it should accept, rather than only comparing outputs.
It caught precisely that during development: the first bridge had no
`SelectedVector` case, so every filter plan fell back.

Widening the fast path is additive. Each addition should arrive with a
conformance test, and none of it changes what a decline means.

Promoting `stream` remains open and needs its own decision, because it is a
change to the streaming contract rather than to performance. Chunked or
incremental columnar execution would remove the tension entirely.
