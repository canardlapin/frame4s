# R6 local release court

Recorded on 2026-07-26 from a dirty development worktree with Temurin JDK
21.0.11, sbt 1.10.5, Scala 3.7.4, and Node 24.1.0. This is repository-side
rehearsal evidence, not a clean-commit or remote-CI receipt.

Command:

```sh
sbt formatCheck docsCheck apiDocs compileAll testAll benchmarkSmoke
```

Result: pass.

- deterministic formatting check: pass;
- eight executable mdoc guides: pass;
- JVM and Scala.js API documentation: generated;
- core JVM: 103 tests passed;
- core Scala.js: 103 tests passed;
- testkit JVM: 23 tests passed;
- testkit Scala.js: 23 tests passed;
- FS2 JVM: 25 tests passed;
- FS2 Scala.js: 21 tests passed;
- all publishable modules and first-contact consumer compiled;
- JMH benchmark sources and generated harness compiled.

Scala 3.7.4 Scaladoc emitted its repeated-classpath diagnostic on all four
modules and one unresolved inherited Cats Effect `IOApp.Simple.run` link while
documenting FS2/JVM. `show fs2JVM/Compile/doc/sources` confirmed that only the
four frame4s FS2 source files entered that documentation task; no frame4s
symbol or source path produced an unresolved link. The diagnostics are
recorded rather than described as warning-free.

The companion
[artifact rehearsal](../2026-07-26-r6-release-rehearsal-final/environment.properties)
published four isolated JVM/Scala.js coordinates, ran both staged consumers,
and verified disposable Ed25519 signatures and SHA-256 sidecars. Its
`worktree=dirty` and `production.signature.status=not-exercised` fields remain
release blockers, not caveats to ignore.
