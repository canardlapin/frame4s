# API reference

The API reference documents symbol-level contracts. Use the guides for complete
tasks and the reference when you need the exact type, return value, or failure
contract.

- `frame4s-core` covers schemas, expressions, logical plans, materialized
  tables, the semantic reference interpreter, and the package-internal
  in-process execution kernels:
  [JVM @VERSION@](https://www.javadoc.io/doc/io.github.canardlapin/frame4s-core_3/@VERSION@/frame4s.html)
  and
  [Scala.js @VERSION@](https://www.javadoc.io/doc/io.github.canardlapin/frame4s-core_sjs1_3/@VERSION@/frame4s.html).
- `frame4s-fs2` covers scoped execution, `FrameRuntime`, `EnginePolicy`, engine
  and source receipts, streams, and CSV and TSV sources:
  [JVM @VERSION@](https://www.javadoc.io/doc/io.github.canardlapin/frame4s-fs2_3/@VERSION@/frame4s/fs2.html)
  and
  [Scala.js @VERSION@](https://www.javadoc.io/doc/io.github.canardlapin/frame4s-fs2_sjs1_3/@VERSION@/frame4s/fs2.html).
- `frame4s-arrow` covers the optional JVM Arrow IPC source and sink:
  [JVM @VERSION@](https://www.javadoc.io/doc/io.github.canardlapin/frame4s-arrow_3/@VERSION@/frame4s/fs2.html).

The adapter failure vocabulary is `SourceError`, `SourceFailure`, `SinkError`,
`SinkFailure`, `RuntimeBindingError`, and `RuntimeBindingFailure`. Their
`message` values are safe for ordinary diagnostics; use `cause` or `getCause`
only when an explicit debugging surface should expose platform details.
Delimited adapters additionally expose `NullPolicy`, `DelimitedReadLimits`,
`SourceLocation`, and `SourceExcerpt`; the [CSV and TSV guide](csv-tsv.md)
defines how quoting, null recognition, bounds, and offsets interact.

The typed relational request vocabulary is `RenameRequest`, `DropRequest`,
`UsingKey`, and `SortKey`. Request tuples retain singleton field names so the
compiler can derive the exact result schema and reject missing, duplicate,
colliding, or mistyped requests. The [relational guide](relational-operations.md)
shows the primary forms; the
[operation map](https://github.com/canardlapin/frame4s/blob/main/docs/operations.md#schema-width-envelope)
gives their canonical plan lowerings and measured schema-width envelope.

These links become live only after the corresponding version is published.
For the main user path, return to the
[three-minute quick start](quick-start.md).
