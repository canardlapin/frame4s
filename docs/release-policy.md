# frame4s 0.1 release policy

Status: accepted

Decision owner: `canardlapin`

Ratified: 2026-07-26 through Mote issue
`bd-01KYF1PJC3K8NRGDNNT47QJFF2`

This policy fixes what frame4s `0.1` supports and what it promises. The
[release-readiness plan](release-readiness-plan.md) is the normative capability
scope; this document is the normative support, compatibility, API-freeze, and
release-continuity contract.

## Supported release court

| Component | Supported `0.1` court | Authoritative receipt |
| --- | --- | --- |
| Scala compiler | Scala 3.7.4 | `build.sbt` and CI |
| Normal sbt court | sbt 1.10.5 | `project/build.properties` and CI |
| Central Portal publication runner | sbt 1.12.11 | tag-triggered release workflow and owner preflight |
| JDK and JVM | Eclipse Temurin 21.x | CI and the R6 staged JVM consumer |
| Scala.js toolchain | sbt-scalajs 1.22.0, CommonJS output | `project/plugins.sbt` and CI |
| JavaScript runtime | Node.js 24.x | CI and the R6 staged Scala.js consumer |
| Published platforms | JVM and Scala.js | R6 staged consumers and R7 artifact verification |

Versions outside this table may work, but frame4s makes no `0.1` support claim
for them. A version enters the supported court only when the policy, CI, and
appropriate staged-consumer receipt change together. Scala Native is not a
promised platform.

The newer sbt runner is a publication capability, not a broadened development
support claim. sbt 1.11 and newer supply the Central Portal staging and release
commands used by `sbt-ci-release` 1.11.x; ordinary development and the
cross-platform court remain on the repository-pinned sbt 1.10.5.

The core remains dependency-free and cross-platform. Cats Effect, FS2,
filesystem access, Apache Arrow, and engine integrations remain in adapters.

## Capability scope and admission

The committed and deferred lists in
[`release-readiness-plan.md`](release-readiness-plan.md) are the single
maintainer-approved release scope. A public capability must satisfy exactly one
admission category from that plan: derived convenience, foundational extension,
or boundary adapter. Feature accretion is deferred.

Until R1 is complete, no new public transformation method or logical-plan
operator may land. Signature changes needed to remove a known soundness defect
are permitted because `0.1.0` has not established a compatibility baseline.

## Compatibility

frame4s uses early SemVer during `0.x`:

- patch releases in one `0.y` line preserve binary compatibility unless a
  documented critical correctness or security repair makes that impossible;
- a new `0.y` minor may change source or binary APIs with release notes and a
  migration example;
- semantic-constitution changes always require an explicit constitution
  amendment, laws, release notes, and backend-conformance updates;
- plan serialization is not a public wire format;
- the exact named-tuple schema meaning, query purity, structured-error
  behavior, resource ownership, and logical/physical explain distinction are
  treated as stable design commitments.

R6 configures compatibility tooling using `0.1.0` as the first post-release
baseline. This policy does not claim compatibility with a release that does not
yet exist.

## Release continuity

The desired steady state is two release-capable maintainers. The factual `0.1`
policy is a disclosed single-maintainer path:

- owner: `canardlapin`;
- a second maintainer is desirable but is not represented as current capacity;
- R6 blocks a release candidate until the owner rehearses offline recovery and
  records a non-secret public receipt;
- the rehearsal covers GitHub account recovery, repository administration,
  signing-key recovery or replacement, publishing-credential recovery or
  rotation, and a clean-machine staged publication;
- recovery codes, private keys, tokens, and other secrets never enter the
  repository or the public receipt;
- if the rehearsal cannot be completed, `0.1.0` is not published.

The continuity decision is revisited when a second maintainer is provisioned or
before any stronger long-term support promise.

## Purity and execution

Query construction performs no acquisition and consults no global session,
ambient filesystem, hidden row index, or implicit backend. A query is an
immutable value. Source binding, backend selection, pushdown negotiation,
execution, and ownership occur only at the explicit effectful boundary defined
by
[`design/adr-0001-execution-boundary.md`](design/adr-0001-execution-boundary.md).
