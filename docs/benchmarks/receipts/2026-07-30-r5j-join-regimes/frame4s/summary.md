# frame4s join size and key-order regime court

Full receipt. Every row constructs and blackholes a detached one-to-one join result.
The key/value multiset is identical across orders; only row order changes.

| Rows | Key order | Execution ms/op | ns/row | Allocation MB/op |
|---:|---|---:|---:|---:|
| 1000 | sorted | 0.014 | 14.37 | 0.07 |
| 1000 | right-shuffled | 0.015 | 14.96 | 0.07 |
| 1000 | both-shuffled | 0.034 | 34.23 | 0.07 |
| 4000 | sorted | 0.047 | 11.67 | 0.26 |
| 4000 | right-shuffled | 0.073 | 18.21 | 0.26 |
| 4000 | both-shuffled | 0.043 | 10.64 | 0.26 |
| 16000 | sorted | 0.355 | 22.17 | 1.04 |
| 16000 | right-shuffled | 0.171 | 10.68 | 1.04 |
| 16000 | both-shuffled | 0.163 | 10.22 | 1.04 |
| 64000 | sorted | 1.818 | 28.41 | 4.14 |
| 64000 | right-shuffled | 1.225 | 19.14 | 4.14 |
| 64000 | both-shuffled | 1.267 | 19.79 | 4.14 |
| 256000 | sorted | 5.790 | 22.62 | 16.54 |
| 256000 | right-shuffled | 6.177 | 24.13 | 16.54 |
| 256000 | both-shuffled | 5.959 | 23.28 | 16.54 |
| 1000000 | sorted | 29.784 | 29.78 | 65.17 |
| 1000000 | right-shuffled | 30.116 | 30.12 | 65.17 |
| 1000000 | both-shuffled | 29.327 | 29.33 | 65.17 |
| 4000000 | sorted | 190.000 | 47.50 | 260.68 |
| 4000000 | right-shuffled | 185.287 | 46.32 | 260.68 |
| 4000000 | both-shuffled | 183.309 | 45.83 | 260.68 |

Median decode/build/probe/materialize/checksum attribution is in `stage-summary.tsv`. `crossover-candidates.tsv` reports the change in per-row cost
between every adjacent size. Exact output checksums and deterministic key
fingerprints are in `validation.tsv`.
