# Central Portal route probe

Status: pass

This probe copied the current source snapshot into a disposable directory,
initialized a clean Git repository, committed the snapshot, and tagged that
commit `v0.1.0`. It then loaded the tagged build with sbt 1.12.11 and
`sbt-ci-release` 1.11.2.

The tagged build reported:

- version `0.1.0`;
- `isSnapshot = false`;
- the `coreJVM` stable publication resolver as `local-staging`;
- the `sonaRelease` command as present;
- the CI publication command as `+publishSigned`.

An additional invocation removed all four publication/signing environment
variables before calling `ci-release`. It reported that secret variables were
unavailable and performed no release action.

The probe supplied no signing or Central Portal credentials and performed no
publication. Its purpose was to test the tag-derived version and the stable
artifact route, which an interactive `set version` probe cannot model
reliably.

After the route probe, the complete repository court passed under both the
normal sbt 1.10.5 runner and the release-only sbt 1.12.11 runner:

```text
formatCheck docsCheck apiDocs versionPolicyCheck compileAll testAll benchmarkSmoke
```

Each run passed 103 core tests and 23 law/testkit tests on both JVM and
Scala.js, plus 25 FS2 JVM tests, 21 FS2 Scala.js tests, eight executable mdoc
guides, compatibility checks, and JMH generation. A fresh
[isolated artifact rehearsal](../2026-07-26-r6-release-rehearsal-central/environment.properties)
also passed for four JVM/Scala.js coordinates and both staged consumers.

The source snapshot came from a dirty development worktree. This receipt
therefore proves the configured routing behavior, not R7 candidate identity or
clean-commit readiness. During the disposable probe JGit also attempted to
write a user-level filesystem-attribute cache outside the sandbox and emitted
a permission diagnostic; project loading and all inspected settings completed
successfully.
