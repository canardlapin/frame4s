# frame4s join size and key-order regime court

Full receipt. Every row constructs and blackholes a detached one-to-one join result.
The key/value multiset is identical across orders; only row order changes.

| Rows | Key order | Execution ms/op | ns/row | Allocation MB/op |
|---:|---|---:|---:|---:|
| 1000 | sorted | 0.007 | 6.81 | 0.05 |
| 1000 | right-shuffled | 0.007 | 6.55 | 0.05 |
| 1000 | both-shuffled | 0.007 | 6.84 | 0.05 |
| 256000 | sorted | 2.421 | 9.46 | 8.20 |
| 256000 | right-shuffled | 4.312 | 16.84 | 12.39 |
| 256000 | both-shuffled | 4.228 | 16.52 | 12.39 |
| 1000000 | sorted | 9.415 | 9.41 | 32.01 |
| 1000000 | right-shuffled | 20.157 | 20.16 | 48.79 |
| 1000000 | both-shuffled | 19.927 | 19.93 | 48.79 |
| 4000000 | sorted | 37.719 | 9.43 | 128.01 |
| 4000000 | right-shuffled | 130.623 | 32.66 | 195.12 |
| 4000000 | both-shuffled | 127.988 | 32.00 | 195.12 |

Median decode/build/probe/materialize/checksum attribution is in `stage-summary.tsv`. `crossover-candidates.tsv` reports the change in per-row cost
between every adjacent size. Exact output checksums and deterministic key
fingerprints are in `validation.tsv`.
