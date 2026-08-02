# R5k.4 matched endpoints, post parallel gather

The [R5k.1 matched-endpoint protocol](../2026-07-30-r5k1-matched-endpoints/admission.md)
re-run after the [chunk-parallel gather admission](../2026-08-01-r5k4-parallel-gather/admission.md).
Same contract as the R5k.3 restatement: three interleaved process rounds,
identical four-column stable-left one-to-one joins at 1M and 4M rows,
exact ordered SHA-256 digests and column sums matched across frame4s,
pandas 3.0.1, pinned Polars 1.43.1, and default-threaded (14) Polars
1.43.1, with the pip-provisioned interpreter for the yanked Polars pin
recorded in each component's `environment.properties`.

## Construction ratios, deep-materialized, round-paired medians

| Rows | Order | f4s ms | f4s/pandas | f4s/Polars 1t | f4s/Polars default |
|---:|---|---:|---:|---:|---:|
| 1M | sorted | 12.75 | 7.02x | **0.31x** | 1.34x |
| 1M | right-shuffled | 40.17 | 1.63x | **0.72x** | 2.63x |
| 1M | both-shuffled | 40.39 | 1.50x | **0.74x** | 2.66x |
| 4M | sorted | 58.57 | 8.19x | **0.29x** | **0.98x** |
| 4M | right-shuffled | 301.02 | 1.75x | **1.02x** | 3.28x |
| 4M | both-shuffled | 299.42 | 1.40x | 1.03x | 3.28x |

Two firsts. frame4s **matches default 14-thread Polars on sorted deep
construction at 4M** (58.57 against 59.68 ms), the first deep endpoint at
parity with the bar a user actually meets; the progression on that cell
is 6.28x behind at R5k.1, 3.15x after typed gather, 0.98x after parallel
gather. And shuffled deep construction now **beats pinned Polars at 1M**
(0.72x–0.74x) and reaches parity at 4M (1.02x–1.03x), where R5k.3 stood
at 0.99x–1.40x.

Against pandas, shuffled deep narrows from 1.68x–2.27x at R5k.3 to
1.40x–1.75x. The sorted pandas column remains its monotonic merge fast
path (1.82–7.15 ms) and is reported as a merge comparison, not a hash
comparison, as ratified in R5j.

## Remaining gaps

- **Shuffled deep vs pandas (1.40x–1.75x) and vs default Polars
  (2.63x–3.28x).** The shuffled gather is random-access on the probe side;
  the sorted/aligned cells show what the same code does when access
  streams. The remaining lever is the join selection itself (probe and
  selection construction dominate the shuffled deep time, not the copy).
- **Matched consumption trails construction** (e.g. sorted 4M 150.78 ms
  against 58.57 ms deep): the serial four-column sum is now a larger share
  of the endpoint than construction and is not yet parallel.

`combined.tsv`, `frame4s-allocation.tsv`, `frame4s-diagnostics.tsv`,
`process-order.tsv`, and `validation.tsv` retain the full evidence;
round directories hold raw samples and per-component environments;
`source-files.sha256` binds the receipt to the measured sources.
