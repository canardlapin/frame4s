# E1 ergonomic API contract court

Date: 2026-07-28

Issue: `bd-01KYG49Y07WBTF6SE79CHXPJB2`

Status: provisional. The landed public API passes the full contract court on
JDK 22 from a dirty development worktree. E1 still requires the same court on
the supported Temurin JDK 21 before it can close.

## What this receipt checks

The downstream `example.ApiContractCourt` package compiles only real public
frame4s calls. It covers:

- typed in-memory, CSV, TSV, CSV-path, and TSV-path single-source constructors;
- exact scalar filtering, raw-column projection, nullable filtering, grouping,
  and aggregation;
- a reusable transformation, conditional composition, and repeated execution;
- scoped collection, streaming, and detached rendering;
- explicit multi-source identities and receipt-bearing join collection.

The negative court checks wrong scalar types, numeric widening, direct raw
`null`, invalid nullable operands, duplicate raw outputs, unnamed computations,
missing fields, foreign-frame expressions, and join output collisions. Its
assertions require the caller-facing mistake to precede internal evidence.

The core parity suite compares concise calls with their canonical expansions,
including plans, schemas, explain output, oracle values, row ordering, and
checked-arithmetic failures. The FS2 suites cover the single-binding facade,
multi-binding rejection, structured failures, and exact-once cleanup on
success, failure, early termination, and cancellation.

## Command

```text
JAVA_HOME=<jdk> scripts/api-contract-court.sh \
  docs/benchmarks/receipts/2026-07-28-e1-api-contract
```

## Recorded result

All recorded tests passed:

- 10 downstream positive and first-diagnostic cases;
- 14 core ergonomic and type-discipline cases on the JVM;
- the same 14 core cases on Scala.js;
- 4 narrow/32-column/48-column compile-court cases on the JVM;
- the same 4 compile-court cases on Scala.js;
- 10 JVM binding/path cases;
- 7 portable binding cases on Scala.js.

The run used Scala 3.7.4, sbt 1.10.5, and OpenJDK 22. It does not satisfy the
supported-toolchain or clean-candidate requirements.

An Eclipse Temurin 21.0.11+10 macOS aarch64 archive is available in a temporary
directory and previously matched vendor SHA-256
`6ebcf221c9b41507b14c098e93c6ead6440b8d9bd154f8ec666c4c73abbdb201`.
Executing that downloaded runtime still requires explicit user authorization.

## Files

- `environment.properties` records the toolchain, commit, platform, and
  worktree state.
- `source-files.sha256` binds the ADR, implementation, downstream court, and
  court script.
- `output.txt` contains sanitized sbt output.
- `timing.txt` contains wall-clock, user, and system time.
