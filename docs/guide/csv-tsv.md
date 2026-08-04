# Read typed CSV and TSV data

CSV ingestion needs an explicit named-tuple schema. The parser checks the file
header and each decoded value against that schema; it does not turn runtime
inference into a compile-time claim.

This example uses an in-memory CSV string so the documentation build can run it
on both the JVM and Scala.js:

```scala mdoc:silent
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import frame4s.*
import frame4s.fs2.*

type CsvObservation = (id: Int, label: String, score: Option[Double])
type CsvSelected = (label: String, score: Option[Double])

val sampleCsv =
  """id,label,score
    |1,alpha,4.0
    |2,東京,
    |""".stripMargin

def selected(frame: Frame[CsvObservation]): Frame[CsvSelected] =
  frame
    .filter(row => row.col("id") > 0)
    .select(row => (row.col("label"), row.col("score")))

val csvSource = CsvFrameSource.binding[IO, CsvObservation](
  sampleCsv,
  CsvSettings(batchSize = 256)
)

def csvProgram: IO[String] = csvSource.render(selected(csvSource.frame))
```

```scala mdoc
csvProgram.unsafeRunSync()
```

On the JVM, use the same schema and query with a filesystem path:

```scala mdoc:compile-only
import _root_.fs2.io.file.Path

def csvFileProgram(path: Path): IO[String] =
  val source = CsvPathSource.binding[IO, CsvObservation](
    path,
    CsvSettings(batchSize = 256)
  )
  source.render(selected(source.frame))
```

`CsvFrameSource` incrementally parses strings, byte streams, or character
streams on both supported platforms. `CsvPathSource` owns the opened JVM file
inside the runtime resource. Use `TsvFrameSource`, `TsvPathSource`, and
`TsvSettings` for tab-delimited input. These constructors are for a source used
alone. A join supplies an explicit `SourceRef` for each input, as described in
[execution and ownership](execution-and-ownership.md).

## Nulls remain distinct from text

Delimited null recognition is quote-aware. The default `NullPolicy` recognizes
an empty field and `null` only when the field is unquoted:

| Input field | Typed value |
| --- | --- |
| empty, unquoted | `None` |
| `""` | `Some("")` |
| `null`, unquoted | `None` |
| `"null"` | `Some("null")` |

The sink uses the same policy. It writes absence as the selected unquoted token
and forcibly quotes real empty strings, token strings, leading or trailing
whitespace, and leading BOM data. Reading the result therefore cannot silently
turn one of those strings into null. `TrimWhitespace` affects only unquoted
fields; quotes protect intentional whitespace as data.

Construct a custom policy before placing it in source or sink settings:

```scala mdoc:silent
val customNullPolicy: Either[NullPolicyError, NullPolicy] =
  NullPolicy.unquotedTokens(
    tokens = Set("", "NA", "missing"),
    writeToken = "NA"
  )

val customCsvSettings: Either[NullPolicyError, CsvSettings] =
  customNullPolicy.map(policy => CsvSettings(nullPolicy = policy))
```

The token set must be nonempty, the write token must be a member, and no token
may be raw `null`. A sink additionally rejects a write token containing its
delimiter, a quote, a line break, leading or trailing whitespace, or a leading
BOM because such a token cannot be emitted safely as one unquoted field under
both coercion modes.

One transport-leading U+FEFF is treated as a BOM. A quoted U+FEFF remains data.
A blank physical record is one empty unquoted field rather than a skipped row.
`SinkReceipt.bytes` reports encoded UTF-8 bytes, so its value remains truthful
for text such as `東京` or emoji.

## Bound parser state

`DelimitedReadLimits` bounds each logical record, decoded field, and retained
diagnostic excerpt. The defaults are 16 Mi characters per record, 4 Mi
characters per field, and 160 characters per excerpt. CSV and TSV use the same
policy on both platforms and through JVM path bindings.

```scala mdoc:silent
val constrainedCsv: Either[DelimitedLimitError, CsvSettings] =
  DelimitedReadLimits
    .create(
      maxRecordChars = 1024 * 1024,
      maxFieldChars = 256 * 1024,
      maxErrorExcerptChars = 80
    )
    .map(limits => CsvSettings(limits = limits))
```

Syntax, width, limit, and scalar-decode failures identify a one-based record
and field plus a zero-based decoded-character offset. Their bounded
`SourceExcerpt` is available for deliberate inspection, but ordinary error,
exception, and log rendering redacts its text.

Byte input is decoded as strict UTF-8. A malformed or truncated code point is
`SourceError.InvalidUtf8(byteOffset, cause)`; it is never replaced silently.
Filesystem failures are `SourceError.Read`, and arbitrary byte or character
stream failures are `SourceError.Upstream`. Direct source streams raise
`SourceFailure`. Execution through a binding raises
`RuntimeBindingFailure(RuntimeBindingError.Source(id, error))`, preserving both
the source identity and the original cause.

`CsvFrameSink.write` and `TsvFrameSink.write` keep their declared
`F[Either[SinkError, Result]]` contract for ordinary upstream and encoding/write
failures. Resource-owning sinks use the same contract for finalizer failures
through `SinkError.Close`; the optional
[Arrow IPC adapter](arrow-ipc.md) is one example. Cancellation and fatal
platform errors are not converted into data. Use `error.message` for logs. It
is bounded and never renders the underlying throwable. `error.cause` and a
failure's `getCause` are the explicit debugging route and may contain sensitive
platform details.

Next, learn how [schemas, nullability, and column errors](schemas-and-errors.md)
are represented.
