# frame4s matched join endpoint court

Full receipt. Process round 3, sequence position
3. `gather-view` is an internal diagnostic and is not a
dataframe comparator numerator. `deep-materialized` owns physical output buffers;
`matched-consumption` additionally sums all four output columns.

| Rows | Key order | Endpoint | ms/op | Allocation MB/op |
|---:|---|---|---:|---:|
| 1000000 | sorted | deep-materialized | 12.630 | 56.53 |
| 1000000 | sorted | gather-view | 9.774 | 32.01 |
| 1000000 | sorted | matched-consumption | 36.057 | 56.54 |
| 1000000 | sorted | prepare | 0.001 | 0.01 |
| 1000000 | right-shuffled | deep-materialized | 40.171 | 73.42 |
| 1000000 | right-shuffled | gather-view | 9.676 | 48.82 |
| 1000000 | right-shuffled | matched-consumption | 63.070 | 73.43 |
| 1000000 | right-shuffled | prepare | 0.001 | 0.01 |
| 1000000 | both-shuffled | deep-materialized | 40.385 | 73.42 |
| 1000000 | both-shuffled | gather-view | 9.843 | 48.82 |
| 1000000 | both-shuffled | matched-consumption | 63.671 | 73.43 |
| 1000000 | both-shuffled | prepare | 0.001 | 0.01 |
| 4000000 | sorted | deep-materialized | 58.362 | 194.04 |
| 4000000 | sorted | gather-view | 47.792 | 96.01 |
| 4000000 | sorted | matched-consumption | 150.768 | 194.04 |
| 4000000 | sorted | prepare | 0.001 | 0.01 |
| 4000000 | right-shuffled | deep-materialized | 301.017 | 293.35 |
| 4000000 | right-shuffled | gather-view | 63.132 | 195.16 |
| 4000000 | right-shuffled | matched-consumption | 393.451 | 293.35 |
| 4000000 | right-shuffled | prepare | 0.001 | 0.01 |
| 4000000 | both-shuffled | deep-materialized | 274.795 | 293.35 |
| 4000000 | both-shuffled | gather-view | 63.906 | 195.16 |
| 4000000 | both-shuffled | matched-consumption | 388.497 | 293.35 |
| 4000000 | both-shuffled | prepare | 0.001 | 0.01 |

Exact stable-left output digests, schema, cardinality, checksums, and fixture
fingerprints are in `validation.tsv`. Stage attribution is in `stage-summary.tsv`.
