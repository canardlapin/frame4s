# R5k.3 typed gather materialization

This receipt admits two changes against the frozen
[R5k.2 anti-fitting baseline](../2026-07-30-r5k2-antifit-regimes/admission.md):

1. **Typed physical gather.** `GatheredVector` now materializes join output
   directly into fresh owned Arrow buffers, one monomorphic copy loop per
   physical family (`Int32`, `Int64`, `Float32`, `Float64`, `Bool`, `Utf8`,
   `Timestamp`), combining selection validity and source validity while
   copying. The previous path boxed every cell through generic
   `ScalarValue` materialization. The fresh buffers are adopted without a
   defensive copy through package-internal `*FromFresh` builders; the public
   `ColumnArray` builders keep their defensive-copy contract, and
   `StorageSuite` proves the public builders remain detached from
   caller-owned arrays.
2. **Run-compressed sorted selection.** The unique sorted-merge path stores
   its selection as contiguous runs (`RunJoinRowSelection`) instead of one
   `Int` pair per output row, activated only above the existing
   run-selection output threshold. Hash joins and duplicate-group merges keep
   the array selection unchanged.

The comparison was produced by `scripts/join-antifit-compare.py` over the
same full court (`scripts/join-antifit-court.sh <receipt> full`): ten
workloads at 1K–1M plus nine at 4M, three endpoints per cell, every cell
validated against the workload's closed-form analytic oracle.

## Gates, all pass

| Gate | Observed | Limit |
|---|---:|---:|
| semantic identity (rows, checksum, strategy, key digests) | 0 mismatches | 0 |
| small-tier latency (≤16K, worst cell) | 0.6339x | ≤1.05x |
| large shuffled latency (worst cell) | 0.9556x | ≤1.03x |
| fallback allocation (hash workloads, worst cell) | 1.0023x | ≤1.05x |
| 1M aligned deep speedup | 3.0712x | ≥1.50x |
| 1M physical-output allocation over gather view | 24.504 MB, 1.0210x payload | ≤1.10x payload |
| 4M aligned deep speedup | 2.7008x | ≥1.50x |
| 4M physical-output allocation over gather view | 98.010 MB, 1.0209x payload | ≤1.10x payload |
| 4M run-selection allocation | 0.7500x | ≤0.80x |

Semantic identity covers every one of the 69 size/workload cells: exact
output cardinality, ordered checksum, executed strategy, and both key
fingerprints are unchanged, so the dispatch boundary ratified by R5k.2 is
untouched. All 150 core and 30 testkit JVM tests pass, including a new
multi-null gather test across all seven physical families and a
`JoinRowRunBuilder` mapping test with outer-join gaps.

## Principal results

Milliseconds and MB per operation, baseline → candidate:

| Rows | Workload | Deep materialized | Deep allocation |
|---:|---|---|---|
| 1M | aligned unique | 140.90 → 45.88 (3.07x) | 204.0 → 56.5 MB |
| 1M | duplicate groups | 268.83 → 103.37 (2.60x) | 392.0 → 97.0 MB |
| 1M | multi-batch sorted | 178.88 → 51.87 (3.45x) | 212.0 → 64.5 MB |
| 1M | fully shuffled | 233.26 → 93.42 (2.50x) | 221.0 → 73.5 MB |
| 4M | aligned unique | 654.68 → 242.40 (2.70x) | 816.0 → 194.0 MB |
| 4M | fully shuffled | 778.30 → 476.50 (1.63x) | 883.4 → 293.3 MB |

The typed gather is not a sorted-fixture special case: the fully shuffled
workload runs the hash join and fallback array selection end to end and
still improves 1.63–2.50x, because the win is in output construction, not
dispatch. Incremental physical-output allocation at 1M aligned rows is
24.50 MB against a 24.00 MB useful payload (four columns of 4+8+4+8 bytes),
so deep materialization now allocates within 2.1% of the bytes the result
actually holds, against 8.5x before.

The run-compressed selection additionally cuts aligned gather-view
allocation at 4M from 128.02 to 96.02 MB (0.75x) and time from 86.31 to
76.28 ms; at 1M gather-view construction falls from 22.67 to 12.18 ms.

## What this does not claim

No cross-engine ratio is published here; the R5k.1 matched-endpoint court
must be re-run to restate the pandas and Polars construction ratios on the
corrected endpoint. The sorted-detection cost structure ratified by R5k.2
(early rejection of shuffled inputs, visible detection cost on sorted
inputs) is preserved but not re-measured beyond the gates above.

`main/` and `scale/` retain JMH JSON and logs, normalized metrics, cold and
warm stage samples, environment details, exact validations, and key
fingerprints. `comparison.tsv` and `gates.tsv` hold every baseline ratio.
`source-files.sha256` binds the receipt to the measured interpreter,
storage builders, fixtures, runner, court script, and comparison script.
