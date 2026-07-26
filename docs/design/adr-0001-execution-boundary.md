# ADR-0001: Explicit source binding and execution

Status: accepted

Date: 2026-07-26

Decision owner: `canardlapin`

## Context

`Frame[S]` is a pure immutable logical plan. Core plans already carry stable
`SourceRef` values, while `frame4s-fs2` exposes resource-scoped
`FrameSource[F]` values and a reference runtime. The current pieces do not yet
form one public boundary for binding several sources, selecting a backend,
negotiating pushdown, and executing the resulting plan.

The boundary must support joins and future optional engines without placing
filesystem access, effects, engine types, or a production columnar executor in
core or `ReferenceInterpreter`.

## Decision

### Plan identity

- Every scan or values leaf carries an immutable `SourceRef`.
- `SourceRef.id` is execution identity. `displayName` is diagnostics only and
  never participates in binding.
- The caller supplies or deliberately derives a stable id when constructing the
  frame. Query construction stores the reference but does not open, inspect, or
  otherwise acquire the source.
- A plan may contain several distinct source ids. Repeated use of one id,
  including a self-join, resolves to one binding in the execution scope.
- Conflicting bindings for one id and missing bindings are structured planning
  or execution-boundary errors.

### Source binding

- One execution receives an immutable, complete binding from each plan
  `SourceId` to one resource-acquired `FrameSource[F]`.
- All bound sources are inspected and checked against the plan schema before
  the backend opens an execution cursor.
- Multi-source acquisition is composed with `Resource`; partial acquisition
  failure releases already acquired sources in reverse order.
- Bindings are invocation-scoped values. There is no global registry, ambient
  session, thread-local source map, or filesystem lookup during query
  construction.

R3 chooses the smallest public names and syntax that implement this contract.
The contract, not this ADR, fixes those surface spellings.

### Backend selection

- The execution backend is selected explicitly at the effectful boundary.
- The semantic reference backend is always available, but it is not selected
  through global mutable state or embedded in a `Frame`.
- Optional optimized backends depend on the core/fs2 contracts; core and fs2 do
  not depend on an engine adapter.
- An optimized backend never replaces `ReferenceInterpreter` as the executable
  semantic oracle.

### Pushdown

- The backend derives a `ScanRequest` from the logical plan and asks each bound
  source which requested capabilities it accepts.
- Every source returns a `PushdownReceipt` separating requested, accepted, and
  residual work.
- Only accepted work may be removed from backend execution. Residual work is
  evaluated with the same semantics as the logical plan.
- Unsupported capability is an explicit residual or structured error, never a
  silent semantic change.
- Pushdown negotiation does not mutate the `Frame` or its logical plan.

### Ownership

Ownership is nested and explicit:

| Resource | Owner and release point |
| --- | --- |
| Bound `FrameSource[F]` | enclosing execution `Resource`; released after all cursors and batches |
| Backend/native state | backend execution `Resource` |
| Execution cursor | stream bracket; closed on success, failure, early stop, or cancellation |
| Emitted batch | batch stream scope; closed after downstream use |
| Materialized `Table[S]` | returned `Resource`; caller may use it only within that scope |

Collection retains or copies batches according to the storage contract before
the stream releases them. Failure or cancellation closes partially retained
batches. No borrowed buffer escapes the resource that owns its native or source
handle, and every owning value is closed exactly once.

### Backend conformance boundary

R2 provides a reusable conformance suite at the effectful adapter/testkit
boundary. A backend is admitted only through:

- the same generated logical plans and source fixtures as the reference
  backend;
- differential values and structured-error results;
- ownership, failure, cancellation, and residual-pushdown laws;
- an explicit capability report;
- JVM and Scala.js execution where the backend claims those platforms.

Core continues to own schemas, expressions, logical plans, normalization,
storage contracts, and the reference interpreter. Backend protocols, resource
acquisition, and source adapters remain outside core.

## Consequences

- A query remains reusable, inspectable, and backend-neutral.
- Joins bind all sources in one scope instead of requiring manual table
  insertion into a reference-only map.
- Backends may optimize aggressively without becoming the semantic authority.
- Source identity and ownership errors become explicit boundary failures.
- R3 must replace the current split workflow with one public resource-scoped
  path, while preserving portable stream/string acquisition and JVM-only path
  adapters.

## Rejected alternatives

- Capturing a table, cursor, source, or effect in `Frame`: breaks purity and
  reusable plans.
- A global Spark-style session or implicit backend: introduces ambient behavior
  and makes ownership non-local.
- Letting sources rewrite plans directly: makes residual semantics and backend
  conformance uninspectable.
- Growing `ReferenceInterpreter` into the optimized engine: couples the oracle
  to the complexity it is meant to judge.
