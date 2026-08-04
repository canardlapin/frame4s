# ADR 0009: Structured adapter failures

Status: accepted and implemented; release certification pending

## Context

The source and sink protocols exposed structured error ADTs, but ordinary I/O
could still escape around them. A missing path, malformed UTF-8, an arbitrary
upstream stream failure, or a resource finalizer failure could arrive as an
unrelated throwable. The runtime translated an existing `SourceFailure` while
letting an equivalent raw source failure pass through unchanged. CSV sinks
could likewise fail their effect instead of returning `Left(SinkError)`.

That made the advertised contract depend on where a failure happened rather
than what it meant. It also encouraged rendering exception messages, which are
unbounded and may contain paths, credentials, or input values.

## Decision

Every built-in adapter uses one boundary rule:

- source inspection and planning keep their declared
  `F[Either[SourceError, A]]` result;
- acquisition, batch streams, and finalization raise `SourceFailure` for every
  ordinary nonfatal failure;
- a `FrameSink.write` invocation returns every ordinary nonfatal failure as
  `Left(SinkError)`;
- cancellation remains cancellation, and throwables outside Scala's
  `NonFatal` set remain on the effect runtime's fatal path.

Source operations distinguish `Open`, `Read`, `InvalidUtf8`, `Upstream`,
`Close`, and the value-level `Closed` state. Sink operations distinguish
`Write`, `Upstream`, and `Close`. Existing schema, parser, storage, and encoding
errors retain their more precise cases.

Operational error cases retain the original throwable. `SourceFailure`,
`SinkFailure`, and `RuntimeBindingFailure` expose that throwable through
`getCause`; the corresponding error ADT exposes it through `cause`. Public
messages never render the cause. Messages are deterministic, control-safe, and
bounded to 256 characters. Decoding errors redact the rejected value, and CSV
header mismatches report the first mismatching column rather than echoing the
header.

`FrameRuntime` adds the source identity and translates acquisition,
inspection, planning, streaming, and finalization consistently. A translated
`RuntimeBindingFailure` retains the adapter cause instead of replacing it.

Portable byte sources use a strict incremental UTF-8 decoder. `InvalidUtf8`
includes the failing byte offset without rendering the cause. Invalid leading
bytes, invalid continuations, overlong encodings, surrogate code points,
out-of-range code points, and truncated final code points all become
`SourceError.InvalidUtf8`. The decoder preserves code points split across
arbitrary FS2 chunks. It does not silently insert the Unicode replacement
character.

## Consequences

Callers can branch on stable ADTs and source identities without parsing prose.
Debugging tools may inspect `cause` or `getCause` explicitly, with the usual
care required for sensitive exception details. Logs and user-facing receipts
should use `message`, whose content is safe and bounded.

Adapter authors must wrap their acquisition, stream, write, and finalizer
edges. Returning `Either` from inspection or `write` does not justify leaving
ordinary failures on a second, undocumented throwable route.

The contract does not turn cancellation or fatal platform failure into data.
It also does not claim that a sink can roll back bytes already written before a
failure; a structured error classifies the failure, not transactional output.

## Court

The portable JVM and Scala.js court covers upstream character and byte streams,
malformed UTF-8, acquisition, inspection, planning, stream execution,
finalization, sink write/upstream/close classification, bounded messages,
cause retention, cancellation, and the fatal classifier. JVM-only tests add
missing paths, path UTF-8 failures, Arrow acquisition, and Arrow sink upstream
failure.
