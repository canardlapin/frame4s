# frame4s join-shape regime court

Full receipt. Every row constructs and blackholes a detached join result.

| Rows | Workload | Key order | Execution ms/op | Allocation MB/op |
|---:|---|---|---:|---:|
| 1000000 | anti-sparse | both-shuffled | 2.877 | 19.32 |
| 1000000 | join-skewed | both-shuffled | 0.588 | 12.08 |
| 1000000 | join-sparse | both-shuffled | 3.606 | 17.00 |
| 1000000 | semi-sparse | both-shuffled | 3.465 | 16.16 |
| 4000000 | anti-sparse | both-shuffled | 12.056 | 77.22 |
| 4000000 | join-skewed | both-shuffled | 2.013 | 48.08 |
| 4000000 | join-sparse | both-shuffled | 12.534 | 67.83 |
| 4000000 | semi-sparse | both-shuffled | 12.391 | 64.52 |

Exact checksums and deterministic fixture fingerprints are in
`validation.tsv`; individual JMH samples are in `raw/jmh.json`.
