# frame4s ecosystem readiness

frame4s is an independent project under the `frame4s` package and
`frame4s-core`/`frame4s-fs2` artifact family. Neither Typelevel membership nor
`org.typelevel` coordinates are claimed.

## Independence contract

The core and FS2 modules have no dependency on ScalaFIM domain modules or Gale.
The extracted source boundary and originating commit are recorded in
[`../PROVENANCE.md`](../PROVENANCE.md). JVM and Scala.js tests must pass without
the ScalaFIM repository or any sibling checkout.

The repository starts with neutral `io.github.canardlapin` coordinates. A
Typelevel conversation should be affiliate-first and should happen only after
maintainers, release signing, compatibility policy, and support expectations
are real. Acceptance by an external organization is not a release gate.

## License and provenance

The project uses Apache License 2.0, an OSI-approved license commonly used in
the Scala ecosystem. The repository includes the complete license text and an
extraction provenance record. Before external publication, maintainers must
audit every copied file and dependency for compatible provenance. Generated
fixtures must record their generator and upstream license.
Contributors must certify that they have the right to submit their changes; a
Developer Certificate of Origin sign-off is the default proposed mechanism.

This license applies to frame4s, not to unrelated ScalaFIM modules.

## Conduct, security, and maintenance

The project adopts the Typelevel Code of Conduct and must publish a private
security-reporting channel before accepting outside contributions.
Security reports should receive acknowledgement within seven days; embargo and
disclosure timing are agreed with the reporter. Public issues are appropriate
for ordinary correctness and performance bugs, not undisclosed vulnerabilities.

At least two maintainers should be able to release. A release requires clean
JVM and Scala.js tests, the standalone extraction rehearsal, dependency review,
and published semantic/benchmark receipts. If maintenance capacity falls below
that level, the project should say so prominently and avoid compatibility
promises it cannot sustain.

## Compatibility intent

During `0.x`, source compatibility is best-effort and semantic changes require
release notes and migration examples. The following are treated as especially
stable: logical null semantics, named-tuple schema meaning, plan purity,
resource ownership, and the distinction between logical and physical explain.
Binary compatibility checking should be introduced before `1.0`; no binary
compatibility promise is made by the current snapshot.

Serialized plans are not yet a public wire format. Arrow IPC compatibility is
delegated to Apache Arrow specifications and tested through the JVM adapter.
CSV behavior is controlled by explicit schema, delimiter, null-token, and
coercion options rather than ambient inference.

Owning CSV and Arrow IPC source constructors return Cats Effect `Resource`
values. Collection and streaming similarly bracket materialized tables,
execution cursors, and emitted batches. These lifetime contracts include
failure and cancellation paths; manual source closure is not part of the public
adapter API.

## Scope and support

frame4s owns a small typed relational algebra, Arrow-compatible local storage,
lawful normalization, a semantic reference interpreter, and resource-safe
source/sink protocols. It does not promise a production vectorized engine,
distributed execution, spill, Parquet pushdown, dataframe convenience parity,
or statistical modeling. Production engines and additional formats are
optional adapters and must report accepted and residual capabilities.

Lawful normalization includes structured-error behavior: a rewrite that would
evaluate checked arithmetic on additional rows, or suppress an error by moving
work behind a limit, is not eligible unless the moved expression is total.
