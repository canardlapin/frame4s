# polars join size and key-order regime court

Full receipt.

Thread count: 14. Every row eagerly constructs a completed join result.
The key/value multiset is identical across orders; only row order changes.

| Rows | Key order | Execution ms/op | ns/row |
|---:|---|---:|---:|
| 1000 | sorted | 0.212 | 212.10 |
| 1000 | right-shuffled | 0.218 | 218.46 |
| 1000 | both-shuffled | 0.212 | 212.46 |
| 4000 | sorted | 0.265 | 66.21 |
| 4000 | right-shuffled | 0.256 | 63.96 |
| 4000 | both-shuffled | 0.263 | 65.79 |
| 16000 | sorted | 0.340 | 21.23 |
| 16000 | right-shuffled | 0.337 | 21.09 |
| 16000 | both-shuffled | 0.340 | 21.25 |
| 64000 | sorted | 0.767 | 11.98 |
| 64000 | right-shuffled | 0.778 | 12.15 |
| 64000 | both-shuffled | 0.768 | 12.00 |
| 256000 | sorted | 2.010 | 7.85 |
| 256000 | right-shuffled | 2.103 | 8.21 |
| 256000 | both-shuffled | 2.094 | 8.18 |
| 1000000 | sorted | 7.844 | 7.84 |
| 1000000 | right-shuffled | 8.246 | 8.25 |
| 1000000 | both-shuffled | 8.351 | 8.35 |
| 4000000 | sorted | 48.748 | 12.19 |
| 4000000 | right-shuffled | 54.858 | 13.71 |
| 4000000 | both-shuffled | 54.082 | 13.52 |

Individual samples are in `raw/samples.csv`; exact cardinality,
value-binding invariants, and deterministic key fingerprints are in
`validation.tsv`.
