# E4 onboarding and documentation court

Date: 2026-07-28

Issue: `bd-01KYG49YKPCY9Q8XJEHX4KM4ER`

Status: provisional. The first-contact program, executable guides, API
documentation, and focused execution suites pass on JDK 22 from a dirty
development worktree. E4 can be functionally reviewed, but E5 still requires
the same artifacts from the clean JDK 21 candidate.

## Reader path checked

The README begins with a named-tuple schema, in-memory rows, one reusable
`Frame` query, detached bounded rendering, and its output. It links the
three-minute quick start, typed CSV/TSV guide, and ownership guide.

The executable guide then adds concepts in this order:

1. schema-as-type, pure query construction, and detached rendering;
2. typed CSV/TSV construction with the same query;
3. compile-time fields, `Option` nullability, and explicit names for computed
   columns;
4. relational operations;
5. scoped table collection and batch streaming;
6. explicit identities, runtime acquisition, and receipts for multi-source
   execution.

Simple detached examples contain no `SourceRef`, `FrameRuntime`, `Resource`
import, manual `Either` lifting, redundant alias for a retained column,
internal API, partial `.get`, or unsafe cast. The advanced ownership example
keeps `Resource`, explicit source identities, `FrameRuntime`, and
`collectWithReceipt` visible because those are real choices.

The guide states that the `0.1` runtime is a semantic reference backend and
makes no unadmitted comparative performance claim.

## Recorded result

The court passed:

- 10 downstream public-API and diagnostic cases;
- the runnable first-contact program, with the expected two-row bounded table;
- 11 mdoc inputs and 10 rendered Laika pages;
- JVM and Scala.js Scaladoc for both published modules;
- 20 JVM source, binding, path, and lifecycle cases;
- 17 Scala.js source, binding, and lifecycle cases.

The generated site inventory contains the landing page, quick start, CSV/TSV,
schemas/errors, relational operations, dynamic schemas, ownership/execution,
semantics/explain, API reference, and release/contribution pages.

## Command

```text
JAVA_HOME=<jdk> scripts/first-use-court.sh \
  docs/benchmarks/receipts/2026-07-28-e4-onboarding
```

## Limits

This run used Scala 3.7.4, sbt 1.10.5, and OpenJDK 22. It is not a clean
candidate or supported-toolchain receipt. Staged consumers against published
candidate artifacts are part of E5 rather than this source-project court.

## Files

- `environment.properties` records the toolchain, commit, platform, and
  worktree state.
- `source-files.sha256` binds the README, guides, public API, first-contact
  program, and staged-consumer sources.
- `output.txt` records first contact, mdoc/Laika, Scaladoc, and the focused JVM
  and Scala.js execution suites from one clean sbt session.
- `timing.txt` records wall-clock, user, and system time for that court.
