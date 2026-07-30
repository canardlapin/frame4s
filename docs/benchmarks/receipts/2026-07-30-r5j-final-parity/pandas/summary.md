# pandas join size and key-order regime court

Full receipt.

Thread count: 1. Every row eagerly constructs a completed join result.
The key/value multiset is identical across orders; only row order changes.

| Rows | Key order | Execution ms/op | ns/row |
|---:|---|---:|---:|
| 1000 | sorted | 0.203 | 202.53 |
| 1000 | right-shuffled | 0.231 | 230.74 |
| 1000 | both-shuffled | 0.228 | 228.23 |
| 4000 | sorted | 0.208 | 51.92 |
| 4000 | right-shuffled | 0.258 | 64.54 |
| 4000 | both-shuffled | 0.253 | 63.31 |
| 16000 | sorted | 0.229 | 14.31 |
| 16000 | right-shuffled | 0.389 | 24.34 |
| 16000 | both-shuffled | 0.393 | 24.58 |
| 64000 | sorted | 0.313 | 4.90 |
| 64000 | right-shuffled | 1.113 | 17.40 |
| 64000 | both-shuffled | 1.077 | 16.82 |
| 256000 | sorted | 0.649 | 2.54 |
| 256000 | right-shuffled | 4.358 | 17.02 |
| 256000 | both-shuffled | 4.576 | 17.88 |
| 1000000 | sorted | 1.963 | 1.96 |
| 1000000 | right-shuffled | 23.320 | 23.32 |
| 1000000 | both-shuffled | 27.653 | 27.65 |
| 4000000 | sorted | 7.557 | 1.89 |
| 4000000 | right-shuffled | 176.820 | 44.21 |
| 4000000 | both-shuffled | 217.476 | 54.37 |

Individual samples are in `raw/samples.csv`; exact cardinality,
value-binding invariants, and deterministic key fingerprints are in
`validation.tsv`.
