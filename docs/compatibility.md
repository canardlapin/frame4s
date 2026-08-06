# Compatibility and migration

frame4s uses early SemVer while the public version is below `1.0`.

- Patch releases in one `0.y` line preserve binary and source compatibility
  unless a critical correctness or security repair makes that impossible.
- A new `0.y` minor may change source or binary APIs. Its release notes must
  list every public break and include a migration example.
- A semantic change is never hidden inside a compatibility label. It requires
  a constitution amendment, reference-interpreter behavior, cross-platform
  laws, backend conformance, and release notes.

`0.1.0` becomes the first compatibility baseline only after it is publicly
released. The build intentionally configures no fictional pre-release
baseline. Beginning with the next release line, `sbt-version-policy` compares
against `0.1.0`.

Release candidates in the `0.1.0-RCn` line are immutable published artifacts,
but they carry no compatibility promise and are not baselines: an RC may differ
from a later RC and from stable `0.1.0` without a version-policy obligation.
Consumers participating in the soak pin one exact RC version and move to
`0.1.0` at promotion.

## Stable design commitments

Within the `0.1.x` line:

- named-tuple field names, order, Scala types, and `Option` nullability define
  the typed schema;
- query construction remains pure and immutable;
- invalid dynamic planning returns structured `FrameError` values;
- source acquisition, backend selection, execution, and ownership remain at an
  explicit resource boundary;
- checked integral arithmetic, SQL three-valued logic, null grouping,
  documented NaN behavior, and declared ordering remain the semantic contract;
- `Table` remains a materialized read view, not a second eager transformation
  algebra.

Logical plans and physical plans are inspectable values, but their serialized
representation is not a public wire format. Persisting them across releases is
unsupported.

## Initial-release migration

`0.1.0` has no preceding public frame4s release, so there is no public migration
to perform. The pre-release work deliberately renamed the ambiguous dynamic
`variance` aggregate to `variancePop`, made expression provenance explicit,
made output-name collisions fail before execution, and connected source
bindings to the normal runtime. Code written against an earlier checkout must
follow the final `0.1.0` examples and compiler diagnostics.

The independent staged-consumer projects compile and run the documented typed
and dynamic entry points from candidate Maven artifacts on both JVM and
Scala.js. They are the executable source-compatibility specimens for the first
release.
