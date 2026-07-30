# polars join size and key-order regime court

Full receipt.

Thread count: 1. Every row eagerly constructs a completed join result.
The key/value multiset is identical across orders; only row order changes.

| Rows | Key order | Execution ms/op | ns/row |
|---:|---|---:|---:|
| 1000 | sorted | 0.061 | 61.16 |
| 1000 | right-shuffled | 0.060 | 60.27 |
| 1000 | both-shuffled | 0.061 | 61.19 |
| 4000 | sorted | 0.108 | 27.12 |
| 4000 | right-shuffled | 0.110 | 27.47 |
| 4000 | both-shuffled | 0.110 | 27.38 |
| 16000 | sorted | 0.301 | 18.82 |
| 16000 | right-shuffled | 0.314 | 19.62 |
| 16000 | both-shuffled | 0.308 | 19.24 |
| 64000 | sorted | 1.184 | 18.50 |
| 64000 | right-shuffled | 1.229 | 19.21 |
| 64000 | both-shuffled | 1.235 | 19.29 |
| 256000 | sorted | 8.160 | 31.88 |
| 256000 | right-shuffled | 8.279 | 32.34 |
| 256000 | both-shuffled | 8.541 | 33.36 |
| 1000000 | sorted | 50.422 | 50.42 |
| 1000000 | right-shuffled | 50.885 | 50.88 |
| 1000000 | both-shuffled | 52.830 | 52.83 |
| 4000000 | sorted | 281.797 | 70.45 |
| 4000000 | right-shuffled | 358.418 | 89.60 |
| 4000000 | both-shuffled | 267.141 | 66.79 |

Individual samples are in `raw/samples.csv`; exact cardinality,
value-binding invariants, and deterministic key fingerprints are in
`validation.tsv`.
