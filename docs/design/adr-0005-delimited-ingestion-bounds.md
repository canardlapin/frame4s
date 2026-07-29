# ADR-0005: Bound delimited ingestion state and diagnostics

Status: proposed for the post-0.1 ingestion epic

## Context

CSV and TSV inputs are incrementally decoded, but the original parser retained
an entire logical record and all records completed by one upstream chunk.
Schema width was checked only when a batch was built. A quoted field or a large
source chunk could therefore retain an amount of data chosen by the input, and
an extra field was not rejected until the row ended. Errors identified a
logical row but did not share a field or absolute source position.

The semantic reader must remain a portable reference implementation. It needs
predictable failure and resource behavior, not dialect inference, recovery, or
a production columnar parsing engine.

## Decision

`DelimitedReadLimits` is the one policy used by CSV and TSV readers. Its finite
defaults are:

- 16,777,216 decoded `Char` values per logical record;
- 4,194,304 decoded `Char` values per decoded field; and
- 160 decoded `Char` values per retained error excerpt.

Record size counts delimiters, quotes, and quoted line breaks, but excludes the
terminating line break. Field size counts the decoded value. Bounds count
decoded Scala `Char` values, not bytes or Unicode code points, so offsets have
the same meaning on the JVM and Scala.js. `DelimitedReadLimits.create` rejects
non-positive bounds and a field bound larger than the record bound before a
source policy can be constructed.

The parser slices every upstream chunk to at most 4,096 characters before
parsing it. Completed-record staging is therefore bounded independently of an
upstream chunk's size. The current record and field are bounded by policy, and
downstream records remain bounded by the existing batch size.

`SourceLocation` is shared by decode, syntax, width, and limit failures:
records and fields are one-based and the absolute decoded-character offset is
zero-based. `SourceExcerpt` records its start offset and whether text was
discarded before or after the retained excerpt. Parser failures retain a
bounded raw-record suffix; scalar decode failures retain a bounded raw-field
prefix, including source quotes and escapes when present.

When a delimiter would open field `schema.size + 1`, the parser fails at that
delimiter. It does not await the rest of the logical record or source. Rows
with too few fields still fail when their record terminator or end-of-input is
observed.

CSV and TSV settings and JVM path bindings forward the same limits. There is no
TSV-specific parser or diagnostic path.

## Consequences

Delimited ingestion now has finite parser state under hostile quoted input and
oversized source chunks. Callers can identify an exact record, field, and
decoded-character position without receiving an unbounded value or record in
the error.

The defaults deliberately allow large scientific records while remaining
finite. Applications with tighter trust boundaries should lower them.

This changes the pre-1.0 `SourceError.Decode` shape and replaces the
CSV-specific `MalformedCsv` case with `MalformedDelimited`. The new cases make
TSV behavior honest and prevent downstream code from reconstructing location
facts from strings.

## Rejected alternatives

- Relying on upstream FS2 chunk sizes leaves memory policy under source control.
- Checking only at batch construction delays excess-width failure and can wait
  forever on an unfinished hostile row.
- Truncating only rendered messages still retains the original unbounded value
  inside the structured error.
- Separate CSV and TSV policies duplicate semantics and allow their safety
  contracts to drift.
- Dialect inference, row recovery, invalid-cell side channels, and compression
  are separate epic children and are not introduced by this parser-safety
  tranche.
