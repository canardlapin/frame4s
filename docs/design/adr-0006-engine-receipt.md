# ADR 0006: Surface the engine decision in the execution receipt

Accepted 2026-08-01.

## Context

ADR-0005 routed the public runtime's materializing `collect` through the
optimized columnar engine with reference fallback, and committed to the
decline reason being "reported rather than swallowed". That commitment was
not honoured: `FrameRuntime` discarded the reason at the fallback branch,
and `ExecutionReceipt` had no engine field at all, so a caller could not
tell which engine answered. Worse, the fallback composition executed plans
twice — when the engine had no kernel for a plan or hit a capability
residual it fell back to the reference interpreter *in-engine*, produced a
correct result, and the runtime then threw that result away and re-ran the
reference path from scratch.

The performance parity plan's Phase 4 requires the promotion to surface
any fallback in the execution receipt, go through the conformance
boundary, and ship with a receipt measuring the public path. ADR-0001's
constraints stand: the reference interpreter remains the semantic oracle,
and unsupported capability is an explicit residual, never a silent
semantic change.

## Decision

1. **The engine reports which side answered.**
   `ColumnarInterpreter.collect` (previously `collectBatches`) returns the
   result *and* the engine receipt. In-engine fallback results are
   returned rather than discarded, so a plan executes exactly once whether
   a kernel or the whole-plan reference fallback answers it. The `Left`
   channel narrows to the cases where this path cannot answer at all:
   execution errors, and results containing a vector with no batch form.
   There the runtime re-runs the reference path, which stays the
   definition of correct behaviour.

2. **`ExecutionReceipt` gains an engine field.**
   `collectWithReceipt` attaches `EngineReceipt(backend, physicalPlan,
   fallback)`: `backend` is `"columnar"` or `"reference"`, matching the
   testkit backend names; `physicalPlan` is the executed engine's plan;
   `fallback` carries the engine's stated reason whenever the reference
   interpreter answered. A fallback is never a failure. Streaming
   executions carry `engine = None`: `stream` deliberately stays on the
   incremental reference cursor per ADR-0005, and its promotion still
   needs its own decision.

3. **`physicalExplain` describes the composite.** Plans the engine admits
   report the columnar operator; plans it declines report the reference
   plan they actually run on, preserving the reference explain format for
   fallback shapes.

## Consequences

- The silent-decline regression named by ADR-0005 — both sides of a
  comparison quietly becoming the reference interpreter — is now
  detectable from the public API, and the runtime suite asserts
  non-vacuity: a supported plan must produce `backend = "columnar"` with
  no fallback, and a bare-source plan must produce `backend = "reference"`
  with its reason.
- Fallback plans no longer execute twice. The reference re-run survives
  only on the error and no-batch-form channels, where the reference path
  is re-executed to surface behaviour in its canonical shape.
- `ExecutionReceipt` is now the single place a caller reads both source
  pushdown negotiation and the engine decision; the testkit
  `BackendReceipt` shape maps onto `EngineReceipt` directly.
- `prepareIndexed` and prepared-join caching remain package-internal per
  ADR-0003 and are unaffected.
