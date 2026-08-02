# ADR 0007: Keep non-blocking runtime execution incremental

Status: accepted

Date: 2026-08-02

## Context

`FrameRuntime.stream` promised scoped incremental batches, but its normal
`SourceBinding` path copied every planned source batch into a `Table` before it
opened the reference cursor. The source adapters parsed incrementally, yet the
runtime erased that property. A downstream `limit` or early stop could not emit
until every source had been drained, and `unionAll` materialized the right
branch before returning a row from the left branch.

The reference interpreter already classifies `Source`, `Project`, `Filter`,
`Limit`, and `UnionAll` as non-blocking. `Aggregate`, `Join`, and `Sort` require
whole-input state in the semantic implementation.

## Decision

For a non-blocking plan, `FrameRuntime.streamWithReceipt` plans each required
source but does not materialize its batches. It composes the planned FS2 streams
according to the logical plan:

- source batches are pulled only on downstream demand;
- project and filter use the reference interpreter's batch transformations;
- limit carries one row budget across batches and stops pulling at zero; and
- union concatenates the left stream before opening the right stream.

Source acquisition, inspection, and pushdown negotiation still occur inside
the enclosing runtime resource. This is required to return a complete source
receipt before the batch stream. Planning a source does not pull a batch.

Plans containing `Aggregate`, `Join`, or `Sort` retain the explicit blocking
route: required sources are materialized inside `Resource`, then the reference
cursor executes. `collect` remains materializing by contract and is unchanged.

Every derived batch has its own stream scope. Completion, failure, early stop,
and cancellation close source and derived batches. At a non-blocking operator
depth, live storage is bounded by the active source batch and the derived
batches needed for that one element; it does not grow with input length.

## Consequences

- A one-row limit can emit after one qualifying source batch and does not drain
  the source.
- A limit satisfied by the left union branch does not pull the right branch.
- Blocking behavior is visible in `ReferenceExecution.shape` and physical
  explain rather than hidden behind a streaming return type.
- Adding a new logical operator requires an explicit choice: implement a
  scoped batch transformation, or classify it as blocking and prove its
  materialization ownership.
