# R5i join performance admission

The single-threaded allocation reduction is admitted. The bounded parallel
probe is rejected.

## Gate results

| Gate | Result | Evidence |
|---|---|---|
| Execution and consumption separated | pass | `candidate/summary.md` records both paths; comparator tooling selects `ExecutionOnly` |
| Exact output cardinalities and checksums unchanged | pass | `baseline/validation.tsv` and `candidate/validation.tsv` are identical |
| JVM and Scala.js join laws | pass | threshold, duplicate, null-key, multi-batch, prepared, inner, outer, semi, and anti tests |
| 1M one-to-one speedup | pass | 129.68 to 29.90 ms/op, 4.34x |
| 1M one-to-one allocation | pass | 110.73 to 65.17 MB/op, below the 85 MB gate |
| Representative allocation regression | pass | all six shapes allocate less than baseline |
| 1K latency regression | pass | consumed one-to-one is 0.036 ms/op versus 0.105 ms/op in the earlier admitted receipt |
| Parallel probe admission | fail | 1.49x faster than allocation-only, but allocation rises 65.17 to 97.07 MB/op |
| Final `compileAll testAll benchmarkSmoke docsCheck` rerun | pass | documentation, JVM and Scala.js compilation, 208 JVM tests, 203 Scala.js tests, and JMH generation passed |
| Repository-wide `formatCheck` | pass | the join source and five committed baseline formatting failures were repaired; the full gate passes |
| Full pinned order receipt | pass | deterministic 1M receipt records pandas 1.946/32.278/40.093 ms and Polars 49.573/48.620/50.361 ms |
| Fresh comparator receipts | pass | pandas and Polars readers prefer and label `ExecutionOnly`; pinned and default Polars were rerun |

The execution-only path constructs a detached `ColumnarResult`, blackholes it,
and closes it. The consumed path additionally folds the exact checksum over
every output cell. Polars comparison ratios must use execution-only timing.

## Stage attribution

The candidate one-to-one profile records median decode 0.64 ms, build 20.27 ms,
probe 17.15 ms, materialize 0.06 ms, and checksum 25.70 ms. The separately
measured JMH endpoints are 29.90 ms execution-only and 68.12 ms consumed; stage
samples attribute work but are not substituted for those benchmark endpoints.

The rejected parallel experiment records a 4.79 ms median probe stage, proving
that immutable-table probing can scale while preserving exact checksums. Its
per-chunk selection builders add too much spare capacity and ownership overhead,
so none of that implementation remains in the admitted source.

## Receipt map

- `baseline/`: same harness before allocation changes.
- `allocation-only/`: isolated allocation candidate used as the parallel
  experiment baseline.
- `parallel-quick/`: provisional rejected parallel-probe receipt.
- `candidate/`: full admitted sequential candidate.
- `small-latency/`: full 1,000-row latency receipt.
- `order/`: full pinned sorted/right-shuffled/both-shuffled comparator receipt.
- `comparators/pandas-1k/`: oracle-backed small-tier pandas receipt.
- `comparators/polars-pinned/`: full 1M single-threaded Polars receipt.
- `comparators/polars-default/`: full 1M default-threaded Polars receipt.

`scripts/join-order-court.sh` makes the sorted/shuffled pandas and Polars
measurement reproducible with pinned versions and deterministic permutations.
The full rerun widens Polars' Int32 key before validation, avoiding an overflow
that would otherwise reject the correct million-row key multiset. Exact source
hashes for the admitted implementation and courts are in `source-files.sha256`.
