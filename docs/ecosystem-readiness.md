# frame4s ecosystem readiness

frame4s is an independent project under the `frame4s` package and the
`frame4s-core`, `frame4s-fs2`, and optional JVM `frame4s-arrow` artifact
family. Neither Typelevel membership nor `org.typelevel` coordinates are
claimed.

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

The project adopts the Typelevel Code of Conduct and provides the private
security-reporting channel documented in
[`SECURITY.md`](../SECURITY.md).
Security reports should receive acknowledgement within three business days;
embargo and disclosure timing are agreed with the reporter. Public issues are
appropriate for ordinary correctness and performance bugs, not undisclosed
vulnerabilities.

Two release-capable maintainers remain the desired steady state. For `0.1`,
frame4s explicitly operates under the single-maintainer continuity policy in
[`release-policy.md`](release-policy.md): `canardlapin` owns the release path,
and R6 blocks publication until a non-secret receipt proves the offline account,
signing, repository, and publishing recovery procedure was rehearsed. This is a
disclosed capacity limit, not a claim that a second maintainer currently
exists. A release still requires clean JVM and Scala.js tests, the standalone
extraction rehearsal, dependency review, and published semantic/benchmark
receipts.

## Compatibility intent

During `0.x`, source compatibility is best-effort and semantic changes require
release notes and migration examples. The following are treated as especially
stable: logical null semantics, named-tuple schema meaning, plan purity,
resource ownership, and the distinction between logical and physical explain.
Binary compatibility checking is active in the candidate court. Because
`0.1.0` is the first public release, it has no configured predecessor and makes
no compatibility claim against pre-release snapshots. The released `0.1.0`
surface becomes the baseline for the `0.1.x` policy.

Serialized plans are not yet a public wire format. Arrow IPC compatibility is
delegated to Apache Arrow specifications and tested through the optional JVM
adapter. `frame4s-arrow` accepts only timezone-free microsecond timestamps in
`0.1`; other Arrow timestamp units return a structured schema error rather
than entering an unbindable runtime schema.
CSV behavior is controlled by explicit schema, delimiter, quote-aware
`NullPolicy`, coercion, and finite ingestion limits rather than ambient
inference. Null tokens apply only to unquoted cells; the sink quotes real data
that could collide with them. First-class TSV source and sink conveniences use
the same codec and safety policy with a fixed tab delimiter. Receipts count
encoded UTF-8 bytes on both supported platforms.

Owning CSV and TSV source constructors in `frame4s-fs2`, and Arrow IPC source
constructors in `frame4s-arrow`, return Cats Effect `Resource` values.
Collection and streaming similarly bracket materialized tables,
execution cursors, and emitted batches. These lifetime contracts include
failure and cancellation paths; manual source closure is not part of the public
adapter API.

Ordinary adapter failures are classified end to end. Sources distinguish open,
read, malformed UTF-8, upstream, and close failures; sinks distinguish
upstream, write, and close failures. Runtime binding retains source identity,
and all operational cases retain an inspectable cause while rendering a
bounded, cause-free public message. Cancellation and fatal errors are not
reclassified as data.

## Scope and support

frame4s owns a small typed relational algebra, Arrow-compatible local storage,
lawful normalization, a semantic reference interpreter, an admitted in-process
columnar path for materializing collection, and resource-safe source/sink
protocols. `Auto` makes columnar selection or typed reference fallback visible;
streaming uses an incremental reference path where the logical shape permits.
It does not promise a production vectorized engine, distributed execution,
spill, Parquet pushdown, dataframe convenience parity, or statistical
modeling. Production engines and additional formats are optional adapters and
must report accepted and residual capabilities. The base FS2 artifact does not
transitively impose Apache Arrow libraries or JVM module openings on CSV- and
TSV-only users.

Lawful normalization includes structured-error behavior: a rewrite that would
evaluate checked arithmetic on additional rows, or suppress an error by moving
work behind a limit, is not eligible unless the moved expression is total.
