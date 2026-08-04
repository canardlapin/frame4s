# frame4s join size and key-order regime court

Full receipt. Every row constructs and blackholes a detached one-to-one join result.
The key/value multiset is identical across orders; only row order changes.

| Rows | Key order | Execution ms/op | ns/row | Allocation MB/op |
|---:|---|---:|---:|---:|
| 1000 | sorted | 0.007 | 7.43 | 0.05 |
| 1000 | right-shuffled | 0.010 | 10.20 | 0.05 |
| 1000 | both-shuffled | 0.007 | 6.85 | 0.05 |
| 256000 | sorted | 5.466 | 21.35 | 12.39 |
| 256000 | right-shuffled | 5.726 | 22.37 | 12.39 |
| 256000 | both-shuffled | 4.432 | 17.31 | 12.39 |
| 1000000 | sorted | 27.404 | 27.40 | 48.79 |
| 1000000 | right-shuffled | 23.263 | 23.26 | 48.79 |
| 1000000 | both-shuffled | 28.702 | 28.70 | 48.79 |
| 4000000 | sorted | 163.662 | 40.92 | 195.12 |
| 4000000 | right-shuffled | 136.390 | 34.10 | 195.12 |
| 4000000 | both-shuffled | 152.515 | 38.13 | 195.12 |

Median decode/build/probe/materialize/checksum attribution is in `stage-summary.tsv`. `crossover-candidates.tsv` reports the change in per-row cost
between every adjacent size. Exact output checksums and deterministic key
fingerprints are in `validation.tsv`.
