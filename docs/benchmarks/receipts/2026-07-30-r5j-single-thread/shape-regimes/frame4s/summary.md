# frame4s join-shape regime court

Full receipt. Every row constructs and blackholes a detached join result.

| Rows | Workload | Key order | Execution ms/op | Allocation MB/op |
|---:|---|---|---:|---:|
| 256000 | anti-sparse | both-shuffled | 3.357 | 4.93 |
| 256000 | anti-sparse | sorted | 3.289 | 4.93 |
| 256000 | join-skewed | both-shuffled | 0.326 | 3.12 |
| 256000 | join-skewed | sorted | 0.360 | 3.12 |
| 256000 | join-sparse | both-shuffled | 3.482 | 4.11 |
| 256000 | join-sparse | sorted | 3.334 | 4.11 |
| 256000 | semi-sparse | both-shuffled | 3.315 | 4.01 |
| 256000 | semi-sparse | sorted | 3.373 | 4.01 |
| 1000000 | anti-sparse | both-shuffled | 14.290 | 19.30 |
| 1000000 | anti-sparse | sorted | 13.360 | 19.30 |
| 1000000 | join-skewed | both-shuffled | 2.143 | 12.05 |
| 1000000 | join-skewed | sorted | 1.269 | 12.05 |
| 1000000 | join-sparse | both-shuffled | 15.259 | 16.10 |
| 1000000 | join-sparse | sorted | 13.182 | 16.10 |
| 1000000 | semi-sparse | both-shuffled | 14.622 | 15.70 |
| 1000000 | semi-sparse | sorted | 12.974 | 15.70 |
| 4000000 | anti-sparse | both-shuffled | 65.687 | 77.20 |
| 4000000 | anti-sparse | sorted | 58.957 | 77.20 |
| 4000000 | join-skewed | both-shuffled | 7.481 | 48.06 |
| 4000000 | join-skewed | sorted | 6.195 | 48.06 |
| 4000000 | join-sparse | both-shuffled | 60.859 | 64.40 |
| 4000000 | join-sparse | sorted | 58.760 | 64.40 |
| 4000000 | semi-sparse | both-shuffled | 59.309 | 62.80 |
| 4000000 | semi-sparse | sorted | 58.483 | 62.80 |

Exact checksums and deterministic fixture fingerprints are in
`validation.tsv`; individual JMH samples are in `raw/jmh.json`.
