# frame4s join-shape regime court

Full receipt. Every row constructs and blackholes a detached join result.

| Rows | Workload | Key order | Execution ms/op | Allocation MB/op |
|---:|---|---|---:|---:|
| 256000 | anti-sparse | both-shuffled | 0.565 | 4.94 |
| 256000 | anti-sparse | sorted | 1.640 | 4.41 |
| 256000 | join-skewed | both-shuffled | 0.178 | 3.13 |
| 256000 | join-skewed | sorted | 0.175 | 3.13 |
| 256000 | join-sparse | both-shuffled | 0.976 | 4.12 |
| 256000 | join-sparse | sorted | 1.537 | 3.59 |
| 256000 | semi-sparse | both-shuffled | 0.955 | 4.02 |
| 256000 | semi-sparse | sorted | 1.574 | 3.49 |
| 1000000 | anti-sparse | both-shuffled | 2.662 | 19.32 |
| 1000000 | anti-sparse | sorted | 6.449 | 17.21 |
| 1000000 | join-skewed | both-shuffled | 0.603 | 12.07 |
| 1000000 | join-skewed | sorted | 0.519 | 12.07 |
| 1000000 | join-sparse | both-shuffled | 4.198 | 16.13 |
| 1000000 | join-sparse | sorted | 5.975 | 14.01 |
| 1000000 | semi-sparse | both-shuffled | 4.119 | 15.73 |
| 1000000 | semi-sparse | sorted | 6.024 | 13.61 |
| 4000000 | anti-sparse | both-shuffled | 10.914 | 77.22 |
| 4000000 | anti-sparse | sorted | 25.920 | 68.81 |
| 4000000 | join-skewed | both-shuffled | 2.394 | 48.07 |
| 4000000 | join-skewed | sorted | 2.419 | 48.07 |
| 4000000 | join-sparse | both-shuffled | 16.716 | 64.43 |
| 4000000 | join-sparse | sorted | 23.706 | 56.01 |
| 4000000 | semi-sparse | both-shuffled | 16.785 | 62.82 |
| 4000000 | semi-sparse | sorted | 24.180 | 54.41 |

Exact checksums and deterministic fixture fingerprints are in
`validation.tsv`; individual JMH samples are in `raw/jmh.json`.
