# R5j.5 allocation-safe parallel-probe decision

This receipt admits parallel probing for large hash joins on the JVM. Sorted
merge remains separate, and Scala.js continues through the sequential scheduler.

The built hash table is immutable during probe. The left input is divided into
contiguous row segments, which workers fill independently. Segment results are
materialized as ordered result batches, so no concatenation copy is required
and left order is deterministic. One-to-one segments use their exact input
length. Low-output inner and semi segments perform a parallel count pass before
filling exact arrays. This avoids the power-of-two spare capacity that caused
the earlier parallel candidate to allocate 97.07 MB/op.

The baseline is commit `020e80e`. Baseline and candidate use the same JMH
runner, 1 GiB pre-touched heap, one fork, three 500 ms warmups, and five 500 ms
measurements. The primary court contains only shuffled keys, so a merge result
cannot be mistaken for parallel hash performance.

## One-to-one decision

| Rows | Key order | Baseline | Candidate | Speedup | Allocation ratio |
|---:|---|---:|---:|---:|---:|
| 1K | right shuffled | 0.006613 ms | 0.006696 ms | 0.99x | 1.001x |
| 1K | both shuffled | 0.006675 ms | 0.006712 ms | 0.99x | 1.001x |
| 64K | right shuffled | 0.716 ms | 0.618 ms | 1.16x | 1.000x |
| 64K | both shuffled | 0.661 ms | 0.600 ms | 1.10x | 1.000x |
| 1M | right shuffled | 19.441 ms | 10.338 ms | 1.88x | 1.001x |
| 1M | both shuffled | 18.603 ms | 10.247 ms | 1.82x | 1.001x |
| 4M | right shuffled | 125.731 ms | 70.040 ms | 1.80x | 1.000x |
| 4M | both shuffled | 123.499 ms | 73.173 ms | 1.69x | 1.000x |

The candidate clears the 1.5x gate at both large tiers and both shuffled
orders. It allocates 48.81 MB/op at 1M, below the 85 MB ceiling and within
0.1% of the sequential path. Neither small guard regresses by 10%.

At 1M, median probe time falls to 1.45--1.53 ms. At 4M it is 9.22--9.39 ms.
Sequential build remains the floor: 8.70--9.29 ms at 1M and 57.16--58.96 ms at
4M.

An isolated 1M sorted control measures 9.295 ms against the 9.404 ms committed
baseline. A larger mixed-parameter run produced unstable sorted samples despite
unchanged profiled merge stages; it is not used to decide either the parallel
or merge claim.

## Sparse and existential decision

Exact-sized low-output segments remove the proportional-sizing candidate's
5.6% sparse allocation miss. Against the admitted sequential kernel:

| Rows | Workload | Speedup | Allocation ratio | vs Polars 1t |
|---:|---|---:|---:|---:|
| 1M | anti sparse | 5.30x | 1.001x | 0.18x |
| 1M | skewed | 3.49x | 1.001x | 0.40x |
| 1M | sparse | 3.67x | 1.002x | 0.33x |
| 1M | semi sparse | 3.58x | 1.001x | 0.29x |
| 4M | anti sparse | 5.22x | 1.000x | 0.11x |
| 4M | skewed | 2.45x | 1.000x | 0.56x |
| 4M | sparse | 3.59x | 1.000x | 0.27x |
| 4M | semi sparse | 2.39x | 1.000x | 0.43x |

All eight shuffled regimes now beat pinned single-thread Polars. The
`shape-regimes/frame4s-proportional` receipt retains the rejected sizing
candidate; `shape-regimes/frame4s` is the admitted exact-sizing result.

## Semantic boundary

The existing threshold test now deliberately misaligns batch and probe-chunk
boundaries. It repeats inner, left outer, semi, and anti joins three times and
checks exact row counts, order guarantees, and checksums. On the JVM it forces
segmented parallel probe; on Scala.js the same source runs sequentially. The
full cross-platform join and differential law courts remain the semantic gate.

`comparison.tsv` and `shape-comparison.tsv` are the machine-readable decision
tables. Component receipts retain raw JMH arrays, stage samples, exact
checksums, allocation measurements, and environment metadata.
