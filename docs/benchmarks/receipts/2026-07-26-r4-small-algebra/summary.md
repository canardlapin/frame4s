# R4 small-algebra receipt

Date: 2026-07-26

This receipt covers the release-plan R4 tranche. The semantic constitution was
amended before the new foundational nodes and operators were admitted.

The public surface now includes projection rename/drop/replace conveniences,
logical distinct, right join as left-join-plus-project, exact-schema
`unionAll`, explicit left-semi/left-anti joins, floating `sqrt`,
`variancePop`, and `stddevPop`. `Table` remains a read view with no duplicated
transformation algebra.

Evidence in `SmallAlgebraSuite` covers typed and dynamic construction,
compile-time diagnostics, exact schemas, lowering/explain/order contracts,
null/NaN/signed-zero/timestamp/UTF-8/dictionary distinct equivalence, branch
failure order, duplicate and null join matches, empty and non-equi existence
joins, short-circuiting, IEEE square root, and analytic population statistics.
The reusable ScalaCheck court independently checks distinct keys, stable union,
semi/anti existence, and batch-boundary invariance. The FS2 court cancels
union, semi, and anti executions and verifies that execution leases return to
their pre-run ownership state.

`gate-output.txt` is the raw `compileAll testAll benchmarkSmoke` JVM and
Scala.js gate. `source-files.sha256` identifies the exact dirty-worktree source
state under test. `type-discipline.txt` records the constraint-escape audit.
The one expected runtime cast is the already documented Scala 3.7 named-tuple
representation retyping boundary in `RowCodec.scala`; R4 adds none.

This is semantic and API-completeness evidence, not an optimized-backend or
comparative-performance claim.
