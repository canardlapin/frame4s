# AGENTS.md

frame4s is a Scala 3 immutable typed local dataframe library, cross-built for
the JVM and Scala.js.

## Layout

- `modules/core`: portable schemas, expressions, plans, storage, normalization,
  and the semantic reference interpreter.
- `modules/fs2`: Cats Effect/FS2 execution, sources, sinks, CSV, and JVM Arrow
  IPC.
- Source roots use `shared`, `jvm`, and `js` with `CrossType.Full`.
- Packages are `frame4s` and `frame4s.fs2`.

## Build and test

- Scala 3.7.4 and sbt 1.10.5.
- Run `sbt compileAll testAll` before declaring work complete.
- Platform-independent tests belong in `shared` and must pass on both JVM and
  Scala.js.
- Keep `-deprecation -feature -unchecked` warning-clean.

## GitHub identity

- This repository belongs to `canardlapin`. Keep `github.account`, commit
  identity, and `origin` repo-local; never change the machine-wide GitHub
  account for frame4s.
- Git pushes use the `github-canardlapin` SSH alias. GitHub API and
  administration commands use `tools/github/gh-repo`, which pins the isolated
  `gh-canardlapin` profile.
- Run `tools/github/doctor` before a push or release operation. A failed `gh`
  token must not be mistaken for failed SSH Git authentication.

## Design contract

- Query construction is pure and immutable; execution is a separate boundary.
- Prefer precise ADTs, singleton names, smart constructors, and structured
  errors over exceptions, sentinels, or runtime recovery of type-level facts.
- `Option[A]` is the only nullable typed-column representation.
- Core must have no external runtime dependency and no JVM-only import.
- Owning sources, streams, batches, and materialized tables have explicit
  `Resource` or scoped lifetime contracts.
- Do not build a production columnar engine into the semantic reference
  interpreter.
- Preserve checked integral arithmetic, SQL three-valued logic, null grouping,
  documented NaN behavior, and error-preserving normalization.
