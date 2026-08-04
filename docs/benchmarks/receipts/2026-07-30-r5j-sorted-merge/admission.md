# R5j.4 sorted merge-join decision

This receipt admits a conditional sorted-key optimization. It does not change
the standing of the hash join.

For large Int32 equi-joins, execution first checks whether both key streams
contain no null values and are monotonically non-decreasing. A strict-key merge
loop handles unique keys. A group loop emits duplicate matches in left-major,
stable right-input order. An inversion or null immediately selects the existing
hash build and probe. Inputs below 16K rows also stay on hash, so detection is
not paid in the 1K latency tier.

Detection, merge selection, and result materialization are all inside the timed
operation. The baseline is commit `0f6f2a4`; baseline and candidate use the same
JMH runner, 1 GiB pre-touched heap, one fork, three 500 ms warmups, and five
500 ms measurements.

## Decision

| Rows | Key order | Baseline | Candidate | Speedup | Allocation ratio |
|---:|---|---:|---:|---:|---:|
| 1K | sorted | 0.007431 ms | 0.006805 ms | 1.09x | 1.001x |
| 1K | right shuffled | 0.010201 ms | 0.006546 ms | 1.56x | 1.002x |
| 1K | both shuffled | 0.006849 ms | 0.006837 ms | 1.00x | 1.001x |
| 256K | sorted | 5.466 ms | 2.421 ms | 2.26x | 0.662x |
| 256K | right shuffled | 5.726 ms | 4.312 ms | 1.33x | 1.000x |
| 256K | both shuffled | 4.432 ms | 4.228 ms | 1.05x | 1.000x |
| 1M | sorted | 27.404 ms | 9.415 ms | 2.91x | 0.656x |
| 1M | right shuffled | 23.263 ms | 20.157 ms | 1.15x | 1.000x |
| 1M | both shuffled | 28.702 ms | 19.927 ms | 1.44x | 1.000x |
| 4M | sorted | 163.662 ms | 37.719 ms | 4.34x | 0.656x |
| 4M | right shuffled | 136.390 ms | 130.623 ms | 1.04x | 1.000x |
| 4M | both shuffled | 152.515 ms | 127.988 ms | 1.19x | 1.000x |

The sorted path clears the required 2x speedup at three adjacent scale tiers.
No shuffled tier regresses, and normalized shuffled allocation is unchanged.
The 1K timings and allocation are unchanged within measurement noise. Sorted
allocation falls by approximately one third because no hash index is built.

Stage profiling includes the monotonic scan. At 1M sorted, median detection is
3.84 ms and merge probe is 4.94 ms. At 4M they are 14.95 and 20.88 ms. The
reported speedups therefore do not treat precondition discovery as free.

## Semantic boundary

Cross-platform tests force the merge path above its threshold and verify:

- strictly increasing one-to-one keys across different batch boundaries;
- stable right duplicate order and left-major duplicate expansion;
- sparse inner, left outer, semi, and anti semantics;
- SQL null exclusion by rejecting streams containing a null to the hash path;
- empty-side behavior on the hash path;
- exact row counts and checksums on JVM and Scala.js.

The path retains detached gathered output and the existing left-order contract.
Prepared indexed joins remain hash joins because their built-side lifetime and
reuse contract is different.

`comparison.tsv` is the machine-readable decision table. `baseline/` and
`candidate/` retain raw JMH arrays, validation checksums, stage samples,
allocation measurements, and environment metadata.
