# polars join size and key-order regime court

Full receipt.

Thread count: 14. Every row eagerly constructs a completed join result.
The key/value multiset is identical across orders; only row order changes.

| Rows | Key order | Execution ms/op | ns/row |
|---:|---|---:|---:|
| 1000 | sorted | 0.252 | 251.82 |
| 1000 | right-shuffled | 0.246 | 245.78 |
| 1000 | both-shuffled | 0.230 | 229.95 |
| 4000 | sorted | 0.266 | 66.62 |
| 4000 | right-shuffled | 0.259 | 64.64 |
| 4000 | both-shuffled | 0.266 | 66.44 |
| 16000 | sorted | 0.383 | 23.96 |
| 16000 | right-shuffled | 0.369 | 23.08 |
| 16000 | both-shuffled | 0.358 | 22.35 |
| 64000 | sorted | 1.006 | 15.71 |
| 64000 | right-shuffled | 1.082 | 16.91 |
| 64000 | both-shuffled | 1.361 | 21.26 |
| 256000 | sorted | 2.876 | 11.23 |
| 256000 | right-shuffled | 2.959 | 11.56 |
| 256000 | both-shuffled | 2.926 | 11.43 |
| 1000000 | sorted | 9.261 | 9.26 |
| 1000000 | right-shuffled | 9.510 | 9.51 |
| 1000000 | both-shuffled | 10.343 | 10.34 |
| 4000000 | sorted | 63.695 | 15.92 |
| 4000000 | right-shuffled | 59.461 | 14.87 |
| 4000000 | both-shuffled | 56.327 | 14.08 |

Individual samples are in `raw/samples.csv`; exact cardinality,
value-binding invariants, and deterministic key fingerprints are in
`validation.tsv`.
