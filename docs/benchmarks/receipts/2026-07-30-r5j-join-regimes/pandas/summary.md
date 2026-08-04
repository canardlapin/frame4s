# pandas join size and key-order regime court

Full receipt.

Thread count: 1. Every row eagerly constructs a completed join result.
The key/value multiset is identical across orders; only row order changes.

| Rows | Key order | Execution ms/op | ns/row |
|---:|---|---:|---:|
| 1000 | sorted | 0.219 | 219.03 |
| 1000 | right-shuffled | 0.256 | 255.88 |
| 1000 | both-shuffled | 0.264 | 264.11 |
| 4000 | sorted | 0.237 | 59.18 |
| 4000 | right-shuffled | 0.313 | 78.20 |
| 4000 | both-shuffled | 0.349 | 87.23 |
| 16000 | sorted | 0.263 | 16.45 |
| 16000 | right-shuffled | 0.454 | 28.37 |
| 16000 | both-shuffled | 0.917 | 57.28 |
| 64000 | sorted | 0.387 | 6.05 |
| 64000 | right-shuffled | 1.474 | 23.03 |
| 64000 | both-shuffled | 1.405 | 21.96 |
| 256000 | sorted | 0.675 | 2.64 |
| 256000 | right-shuffled | 5.240 | 20.47 |
| 256000 | both-shuffled | 5.897 | 23.03 |
| 1000000 | sorted | 2.080 | 2.08 |
| 1000000 | right-shuffled | 29.484 | 29.48 |
| 1000000 | both-shuffled | 44.666 | 44.67 |
| 4000000 | sorted | 8.442 | 2.11 |
| 4000000 | right-shuffled | 231.200 | 57.80 |
| 4000000 | both-shuffled | 242.470 | 60.62 |

Individual samples are in `raw/samples.csv`; exact cardinality,
value-binding invariants, and deterministic key fingerprints are in
`validation.tsv`.
