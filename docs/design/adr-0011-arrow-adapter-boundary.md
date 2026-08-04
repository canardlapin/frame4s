# ADR-0011: Arrow is an optional, lifetime-safe JVM adapter

Status: accepted for `0.1`

## Context

Apache Arrow Vector was previously a direct dependency of the JVM
`frame4s-fs2` artifact. That made CSV-only users resolve Arrow and inherit its
JVM module-access requirement. Decoding also constructed frame4s columns before
the final `RecordBatch` existed, so a later column or batch-validation failure
could strand earlier owners. The source described its input as a byte snapshot
without copying the caller's mutable array.

The runtime can represent four timestamp units, but `TimestampMicros` is the
only public typed timestamp in `0.1`. Accepting other Arrow timestamp units
would create runtime schemas that no public typed schema can bind.

## Decision

Arrow IPC lives in the JVM-only `frame4s-arrow` artifact. It depends on
`frame4s-fs2`; neither base FS2 artifact depends on Apache Arrow or carries an
Arrow-specific JVM option. Programs that use the Arrow artifact start with:

```text
--add-opens=java.base/java.nio=ALL-UNNAMED
```

`ArrowIpcFrameSource.resource` clones its input array synchronously when the
resource value is constructed. Acquisition and decoding therefore observe an
immutable snapshot even if the caller later mutates its array.

Decoding is an incremental ownership transaction. Each successfully decoded
column enters an owned builder. A structured error or nonfatal exception closes
the exact accumulated prefix. If final `RecordBatch` validation fails, every
decoded column is closed before the error is returned. Once a batch succeeds,
its ownership moves into the source resource and is released by that resource.

The `0.1` Arrow boundary admits only timezone-free microsecond timestamp
fields. Source inspection returns `SourceError.SchemaMismatch` for every other
Arrow timestamp unit or timezone. The sink returns `SinkError.InvalidRequest`
for a frame schema with another runtime timestamp unit. Expanding the typed
timestamp family requires a separate API decision.

## Consequences

- CSV and TSV users pay no Arrow dependency or JVM-configuration cost.
- An Arrow consumer must select the optional coordinate and the documented JVM
  opening explicitly.
- Caller-array mutation, partial-column failure, final batch-validation
  failure, and unsupported timestamp units have permanent regression tests.
- Publication rehearsals inspect the base FS2 POM for accidental Arrow
  dependencies and run an external Arrow read/write consumer with the same JVM
  option documented for production.
