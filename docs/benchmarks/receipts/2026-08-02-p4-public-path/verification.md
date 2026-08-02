# Candidate verification

The following command passed before the performance court on Homebrew Java
25.0.1:

```text
sbt benchmarks/scalafmt formatCheck compileAll testAll docsCheck apiDocs benchmarkSmoke
```

Observed test totals:

| Module | JVM | Scala.js |
|---|---:|---:|
| core | 152 | 152 |
| testkit | 30 | 30 |
| fs2 | 40 | 35 |

Executable docs, the rendered documentation site, JVM and Scala.js API docs,
and JMH bytecode generation also passed. `git diff --check` was clean.

The court then ran:

```text
sbt 'benchmarks/runMain frame4s.benchmarks.PublicPathCourtRunner --receipt <absolute-receipt-path> --rows 1000000'
```

The receipt is bound to the working-tree candidate rather than a commit. Java
25 is the locally available runtime; this evidence does not replace the
release checklist's supported-JDK court.
