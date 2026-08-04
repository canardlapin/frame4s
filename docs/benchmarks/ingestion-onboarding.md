# Ingestion and onboarding comparison

Scautable and frame4s solve two different first-contact problems, so this
document deliberately avoids putting Scautable into the relational JMH table.

Scautable's concise fixed-resource form:

```scala
CSV.resource("simple.csv", TypeInferrer.FromAllRows)
```

uses a literal resource at compilation time so a macro can inspect headers and
rows and synthesize tuple types. That is an excellent executable-schema tool,
but its compile-time file access and inferred contract are not equivalent to
opening an arbitrary runtime path or stream.

Scautable also exposes an explicitly typed runtime form,
`CSV.fromTyped[K, V]`, which is the closer ingestion comparison. frame4s takes
the same explicit-contract position: a `SchemaDescriptor[S]` authorizes `S`,
and an owned table or stream remains scoped by `Resource` or `Stream`.
`render` may bracket internally because it returns a detached `String`.
frame4s does not infer a binding schema silently; future inference may emit an
explicit schema suggestion and receipt.

The current frame4s first-use specimen is executable with:

```sh
scripts/first-use-court.sh docs/benchmarks/receipts/local-onboarding
```

Its current committed receipt is
[2026-07-28-e4-onboarding](receipts/2026-07-28-e4-onboarding/summary.md).
The executable lives in a separate sbt project and a package outside
`frame4s`, so it exercises downstream visibility and implicit derivation rather
than relying on package-private access.

The detached one-source path is:

1. declare a readable named-tuple row type;
2. create a typed `CsvFrameSource.binding` or `CsvPathSource.binding` whose
   schema comes from that type;
3. build a pure filter/project from `binding.frame`;
4. call `binding.render(query)` for a bounded detached string.

There is no handwritten runtime `Schema`, manual batch, `ReferenceSources`,
partial `.get`, explicit source identity, runtime setup, internal member, or
unsafe cast. `collect` and `stream` retain visible ownership for an escaping
`Table` or batch stream. Multiple sources and receipt-bearing execution still
use explicit `SourceRef` values and `FrameRuntime`. Portable callers use
`CsvFrameSource.byteBinding` or `characterBinding`; the bounded whole-string
entry point remains an explicitly in-memory convenience. CSV and TSV parsing is
incremental across arbitrary chunks, including UTF-8 code points, quoted
newlines, escaped quotes, and CRLF boundaries.

No throughput ratio is published between the fixed-resource macro path and
runtime acquisition. Such a ratio would conflate compile-time work, runtime
I/O, schema inference, and materialization.
