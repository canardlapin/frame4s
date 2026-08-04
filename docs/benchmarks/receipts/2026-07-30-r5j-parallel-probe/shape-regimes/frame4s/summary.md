# frame4s join-shape regime court

Full receipt. Every row constructs and blackholes a detached join result.

| Rows | Workload | Key order | Execution ms/op | Allocation MB/op |
|---:|---|---|---:|---:|
| 1000000 | anti-sparse | both-shuffled | 2.697 | 19.32 |
| 1000000 | join-skewed | both-shuffled | 0.614 | 12.07 |
| 1000000 | join-sparse | both-shuffled | 4.156 | 16.13 |
| 1000000 | semi-sparse | both-shuffled | 4.085 | 15.73 |
| 4000000 | anti-sparse | both-shuffled | 12.579 | 77.22 |
| 4000000 | join-skewed | both-shuffled | 3.049 | 48.07 |
| 4000000 | join-sparse | both-shuffled | 16.942 | 64.43 |
| 4000000 | semi-sparse | both-shuffled | 24.800 | 62.82 |

Exact checksums and deterministic fixture fingerprints are in
`validation.tsv`; individual JMH samples are in `raw/jmh.json`.
