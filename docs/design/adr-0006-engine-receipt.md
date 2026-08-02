# ADR 0006: Surface the engine decision in the execution receipt

Accepted 2026-08-01; amended 2026-08-02.

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

2. **Engine choice and receipts are typed.** `EngineId` distinguishes
   `Reference` from `Columnar`. `EngineFallbackReason` distinguishes an
   unsupported logical shape, a kernel capability residual, and a columnar
   execution decline. `collectWithReceipt` attaches
   `EngineReceipt(engine, physicalPlan, fallback)`; callers do not compare
   backend or fallback strings. Streaming executions carry no engine receipt
   because they use the incremental reference route.

3. **Selection is explicit at the effectful boundary.** `EnginePolicy.Auto`
   keeps the default behavior: use an admitted columnar kernel and otherwise
   report reference fallback. `ReferenceOnly` bypasses columnar execution.
   `RequireColumnar` returns `EnginePolicyFailure` when the engine cannot
   answer; its required-only collect path does not open the reference
   interpreter. The default
   `SourceBinding` conveniences use `Auto`, while `FrameRuntime.resource`
   accepts an explicit policy for callers that need a stricter contract.

4. **`physicalExplain` follows the selected policy.** `ReferenceOnly` reports
   the reference plan. `Auto` reports an admitted columnar plan or the
   reference plan it would run. `RequireColumnar` reports the columnar plan or
   an explicit unavailable result; it does not describe a reference plan that
   the policy forbids.

## Consequences

- The silent-decline regression named by ADR-0005 is detectable from the
  public API. Runtime tests assert that a supported plan produces
  `EngineId.Columnar`, while an unsupported shape under `Auto` reports
  `EngineId.Reference` and a typed reason.
- Applications that require predictable engine admission can select
  `RequireColumnar` and handle one structured failure instead of inspecting
  explanatory text.
- Fallback plans no longer execute twice. The reference re-run survives
  only on the error and no-batch-form channels, where the reference path
  is re-executed to surface behaviour in its canonical shape.
- `ExecutionReceipt` is now the single place a caller reads both source
  pushdown negotiation and the engine decision; the testkit
  `BackendReceipt` shape maps onto `EngineReceipt` directly.
- `prepareIndexed` and prepared-join caching remain package-internal per
  ADR-0003 and are unaffected.
