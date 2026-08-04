# ADR-0010: Make delimited I/O lossless and bounded

Status: accepted for 0.1.0

## Context

The original CSV and TSV reader discarded whether a field was quoted before it
recognized null tokens. With the default tokens `""` and `"null"`, an absent
value, a real empty string, and a real string containing `null` therefore
decoded to the same logical value. The writer made that corruption silent by
emitting real strings without protective quotes.

Incremental decoding did not by itself bound memory. A hostile quoted field or
one very large upstream chunk could retain input without a finite policy, and
an extra column was not rejected until its record ended. Receipts also reported
Scala string length as bytes, which is false for multibyte UTF-8.

The portable reader is a semantic adapter, not a dialect-inference or recovery
engine. Its first-release obligation is narrower: preserve supported values or
return a structured error, with finite state on both the JVM and Scala.js.

## Decision

Every parsed field retains `CsvCell(text, quoted, location, excerpt)` until
decoding. `NullPolicy` recognizes configured tokens only in unquoted cells. A
quoted cell is always data, including `""`, `"null"`, and any custom token.
The validated policy contains a nonempty token set and chooses one member as
the write token. Its default recognizes `""` and `"null"` and writes null as an
unquoted empty field.

The writer emits null as the selected unquoted token. It forcibly quotes every
real value that is empty, equals a null token, has leading or trailing
whitespace, or begins with U+FEFF. Normal CSV quoting still protects delimiters,
quotes, CR, and LF. This rule applies to every rendered scalar, so a numeric or
Boolean token cannot collide with a custom null policy. A write token that
cannot form one safe unquoted field under either coercion mode is rejected as
`SinkError.InvalidRequest`; that includes leading or trailing whitespace.

`TrimWhitespace` applies only to unquoted cells. Quoting therefore remains an
escape from both null recognition and whitespace coercion, and the sink's
conservative quoting preserves its output under either reader coercion mode.

The parser removes exactly one U+FEFF only when it is the first transport
character. That character still contributes to absolute source offsets. A
quoted leading U+FEFF is data. A blank physical record is one unquoted empty
cell; it is not skipped. CRLF is one record terminator, and quoted line breaks
remain field data.

`DelimitedReadLimits` is the shared CSV and TSV safety policy. Its finite
defaults are:

- 16,777,216 decoded `Char` values per logical record;
- 4,194,304 decoded `Char` values per decoded field; and
- 160 decoded `Char` values per retained error excerpt.

Record size counts delimiters, quotes, and quoted line breaks, but excludes the
terminating line break. Field size counts decoded field text. Bounds and source
offsets count Scala `Char` values, not bytes or Unicode code points, so they
have the same meaning on both platforms. Construction rejects nonpositive
bounds and a field bound larger than the record bound.

The parser slices upstream chunks to at most 4,096 characters. It rejects a
delimiter that would begin field `schema.size + 1` immediately, without
awaiting the source tail. Rows with too few fields fail when their terminator or
end of input is observed.

`SourceLocation` gives one-based record and field numbers plus a zero-based
absolute decoded-character offset. `SourceExcerpt` retains only the configured
prefix or suffix and records truncation. Decode and syntax errors carry these
values for deliberate inspection, while their `message`, `toString`, and
failure rendering redact source text. Public messages remain bounded by the
adapter error contract.

`SinkReceipt.bytes` is the UTF-8 encoded length, not a JVM or JavaScript string
length. Strict byte sources reject malformed or truncated UTF-8 separately as
`SourceError.InvalidUtf8`.

## Consequences

Under the default strict reader, writing and reading again distinguishes null,
empty text, every configured token, leading BOM data, embedded delimiters,
escaped quotes, quoted line breaks, and multibyte Unicode. The same
quote/null/limit semantics apply to CSV and TSV, including JVM path bindings.

Delimited ingestion has finite parser and diagnostic state independently of
upstream chunk size. Applications with tighter trust boundaries can lower the
defaults without selecting a different parser.

This changes the pre-0.1 `CsvReadOptions`, `CsvSettings`, `TsvReadOptions`, and
`TsvSettings` API from raw token sets to `NullPolicy`; sinks use that same
policy instead of an unrelated `nullValue`. `SourceError.Decode` now carries a
location and bounded excerpt, and the CSV-specific `MalformedCsv` case becomes
`MalformedDelimited`.

## Rejected alternatives

- Treating quoted and unquoted tokens alike cannot distinguish syntax from
  data.
- Choosing a rare sentinel merely moves silent corruption to a rarer string.
- Quoting only when RFC syntax requires it leaves empty strings and null-token
  strings ambiguous.
- Trusting upstream FS2 chunk sizes leaves memory policy under source control.
- Truncating only rendered messages still retains an unbounded rejected value
  inside the error.
- Dialect inference, row recovery, and invalid-cell side channels are separate
  features and are not part of this safety contract.
