# frame4s join size and key-order regime court

Full receipt. Every row constructs and blackholes a detached one-to-one join result.
The key/value multiset is identical across orders; only row order changes.

| Rows | Key order | Execution ms/op | ns/row | Allocation MB/op |
|---:|---|---:|---:|---:|
| 1000 | sorted | 0.007 | 6.81 | 0.05 |
| 1000 | right-shuffled | 0.007 | 6.61 | 0.05 |
| 1000 | both-shuffled | 0.007 | 6.68 | 0.05 |
| 64000 | sorted | 0.596 | 9.31 | 2.05 |
| 64000 | right-shuffled | 0.716 | 11.18 | 3.10 |
| 64000 | both-shuffled | 0.661 | 10.32 | 3.10 |
| 1000000 | sorted | 9.404 | 9.40 | 32.01 |
| 1000000 | right-shuffled | 19.441 | 19.44 | 48.79 |
| 1000000 | both-shuffled | 18.603 | 18.60 | 48.79 |
| 4000000 | sorted | 39.203 | 9.80 | 128.01 |
| 4000000 | right-shuffled | 125.731 | 31.43 | 195.12 |
| 4000000 | both-shuffled | 123.499 | 30.87 | 195.12 |

Median strategy and execution-stage attribution is in `stage-summary.tsv`. `crossover-candidates.tsv` reports the change in per-row cost
between every adjacent size. Exact output checksums and deterministic key
fingerprints are in `validation.tsv`.
