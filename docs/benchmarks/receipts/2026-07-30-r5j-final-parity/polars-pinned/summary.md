# polars join size and key-order regime court

Full receipt.

Thread count: 1. Every row eagerly constructs a completed join result.
The key/value multiset is identical across orders; only row order changes.

| Rows | Key order | Execution ms/op | ns/row |
|---:|---|---:|---:|
| 1000 | sorted | 0.058 | 57.74 |
| 1000 | right-shuffled | 0.057 | 57.32 |
| 1000 | both-shuffled | 0.057 | 57.46 |
| 4000 | sorted | 0.105 | 26.32 |
| 4000 | right-shuffled | 0.106 | 26.43 |
| 4000 | both-shuffled | 0.105 | 26.37 |
| 16000 | sorted | 0.293 | 18.32 |
| 16000 | right-shuffled | 0.297 | 18.55 |
| 16000 | both-shuffled | 0.298 | 18.60 |
| 64000 | sorted | 1.097 | 17.14 |
| 64000 | right-shuffled | 1.136 | 17.75 |
| 64000 | both-shuffled | 1.137 | 17.77 |
| 256000 | sorted | 5.795 | 22.64 |
| 256000 | right-shuffled | 6.034 | 23.57 |
| 256000 | both-shuffled | 6.008 | 23.47 |
| 1000000 | sorted | 41.576 | 41.58 |
| 1000000 | right-shuffled | 40.563 | 40.56 |
| 1000000 | both-shuffled | 42.144 | 42.14 |
| 4000000 | sorted | 208.286 | 52.07 |
| 4000000 | right-shuffled | 223.386 | 55.85 |
| 4000000 | both-shuffled | 219.440 | 54.86 |

Individual samples are in `raw/samples.csv`; exact cardinality,
value-binding invariants, and deterministic key fingerprints are in
`validation.tsv`.
