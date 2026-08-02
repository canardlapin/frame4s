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

`stream` keeps reference semantics rather than routing through the columnar
engine. The columnar engine materializes its entire result before yielding
anything. Promoting it under `stream` would convert a non-blocking plan into
materialize-then-emit while the type signature stayed unchanged. ADR-0007
implements the normal `SourceBinding` route directly over scoped FS2 batches
for non-blocking plans and retains explicit reference materialization for
aggregate, join, and sort. `collect` already materializes by contract, so it
gives up nothing.

`ColumnarInterpreter.collect` returns an in-engine reference result when no
kernel matches or a kernel reaches a capability residual. It returns `Left`
only when execution fails or the result has no batch representation. Under the
default `EnginePolicy.Auto`, the runtime then runs the reference path and
reports a typed decline reason. `RequireColumnar` instead returns a structured
policy failure. The reference interpreter remains the definition of correct
behavior, but fallback is never hidden from the caller.

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

Chunked or incremental columnar execution could become a future streaming
engine, but only behind the bounded contract in ADR-0007.
