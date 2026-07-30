# frame4s join size and key-order regime court

Full receipt. Every row constructs and blackholes a detached one-to-one join result.
The key/value multiset is identical across orders; only row order changes.

| Rows | Key order | Execution ms/op | ns/row | Allocation MB/op |
|---:|---|---:|---:|---:|
| 1000 | right-shuffled | 0.007 | 6.70 | 0.05 |
| 1000 | both-shuffled | 0.007 | 6.71 | 0.05 |
| 64000 | right-shuffled | 0.618 | 9.66 | 3.10 |
| 64000 | both-shuffled | 0.600 | 9.38 | 3.10 |
| 1000000 | right-shuffled | 10.338 | 10.34 | 48.81 |
| 1000000 | both-shuffled | 10.247 | 10.25 | 48.81 |
| 4000000 | right-shuffled | 70.040 | 17.51 | 195.16 |
| 4000000 | both-shuffled | 73.173 | 18.29 | 195.16 |

Median strategy and execution-stage attribution is in `stage-summary.tsv`. `crossover-candidates.tsv` reports the change in per-row cost
between every adjacent size. Exact output checksums and deterministic key
fingerprints are in `validation.tsv`.
