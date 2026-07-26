# R3 first-contact and streaming-ingestion receipt

Date: 2026-07-26

Status: passing on the recorded dirty release worktree. The exact R3 source
files are identified by `source-files.sha256`; `environment.properties`
records the base commit and toolchain.

## Downstream first contact

`example.FirstContact` is compiled in the non-published `firstContact` sbt
project, from a package outside `frame4s`. It:

1. declares `(id: Int, label: String, score: Option[Double])`;
2. creates a JVM CSV path binding without a handwritten `Schema`;
3. builds a pure typed filter/project before runtime acquisition;
4. acquires the binding through one `FrameRuntime.resource`;
5. executes through `collectWithReceipt`;
6. reads the exact typed `label` column;
7. renders a bounded table; and
8. closes the file, source, source materialization, output batches, and table
   through the one outer `Resource.use`.

The specimen contains no `.toOption.get`, manual `RecordBatch`,
`ReferenceSources`, package-private member, or unsafe cast. `output.txt`
records the successful Unicode/null/NaN rendering. `timing.txt` is wall-clock
first-contact court time, including sbt startup and any incremental compile;
it is an onboarding receipt, not a data-kernel benchmark.

## Incremental ingestion stress

`FrameIOSuite` passed all ten portable source laws on the JVM, with the same
suite also part of the Scala.js gate. The stress case generates 10,000 CSV
records lazily as a character stream and asserts:

- exactly 10,000 decoded rows;
- no emitted batch exceeds the configured 17 rows; and
- the final batch contains exactly four rows.

The parser state retains only the current record and completed records from the
current input chunk; downstream `chunkN` retains at most the configured output
batch. Thus retained parser/input state is bounded by the current FS2 chunk
plus the largest in-progress quoted record, while decoded storage is bounded
by one output batch. `streaming-stress-output.txt` and
`streaming-stress-timing.txt` are the raw execution receipt.

The same suite covers one-byte UTF-8 chunks, quoted newlines, escaped quotes,
CRLF split handling, terminal empty fields, configurable null tokens, exact
header mismatch, logical row/column decode errors, malformed row widths,
early termination, and input finalization exactly once.

## Scope

This receipt proves the source adapter's incremental/bounded contract and the
public reference-runtime first-contact path. It is not a claim that the
semantic reference backend is an optimized streaming engine: that backend may
materialize bound sources before executing a blocking or reusable reference
plan, and its performance remains governed by the R2/R5 court.
