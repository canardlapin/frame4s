# frame4s join size and key-order regime court

Full receipt. Every row constructs and blackholes a detached one-to-one join result.
The key/value multiset is identical across orders; only row order changes.

| Rows | Key order | Execution ms/op | ns/row | Allocation MB/op |
|---:|---|---:|---:|---:|
| 1000 | sorted | 0.007 | 6.77 | 0.05 |
| 1000 | right-shuffled | 0.007 | 6.70 | 0.05 |
| 1000 | both-shuffled | 0.007 | 6.67 | 0.05 |
| 4000 | sorted | 0.024 | 6.09 | 0.20 |
| 4000 | right-shuffled | 0.024 | 5.98 | 0.20 |
| 4000 | both-shuffled | 0.024 | 5.94 | 0.20 |
| 16000 | sorted | 0.098 | 6.11 | 0.78 |
| 16000 | right-shuffled | 0.097 | 6.05 | 0.78 |
| 16000 | both-shuffled | 0.095 | 5.92 | 0.78 |
| 64000 | sorted | 0.586 | 9.15 | 2.05 |
| 64000 | right-shuffled | 0.628 | 9.82 | 3.10 |
| 64000 | both-shuffled | 0.625 | 9.77 | 3.10 |
| 256000 | sorted | 2.362 | 9.23 | 8.20 |
| 256000 | right-shuffled | 2.251 | 8.79 | 12.40 |
| 256000 | both-shuffled | 2.290 | 8.94 | 12.40 |
| 1000000 | sorted | 9.265 | 9.26 | 32.01 |
| 1000000 | right-shuffled | 9.819 | 9.82 | 48.81 |
| 1000000 | both-shuffled | 9.846 | 9.85 | 48.81 |
| 4000000 | sorted | 37.116 | 9.28 | 128.01 |
| 4000000 | right-shuffled | 67.283 | 16.82 | 195.16 |
| 4000000 | both-shuffled | 68.388 | 17.10 | 195.16 |

Median strategy and execution-stage attribution is in `stage-summary.tsv`. `crossover-candidates.tsv` reports the change in per-row cost
between every adjacent size. Exact output checksums and deterministic key
fingerprints are in `validation.tsv`.
