# frame4s matched join endpoint court

Full receipt. Process round 1, sequence position
1. `gather-view` is an internal diagnostic and is not a
dataframe comparator numerator. `deep-materialized` owns physical output buffers;
`matched-consumption` additionally sums all four output columns.

| Rows | Key order | Endpoint | ms/op | Allocation MB/op |
|---:|---|---|---:|---:|
| 1000000 | sorted | deep-materialized | 12.749 | 56.53 |
| 1000000 | sorted | gather-view | 9.834 | 32.01 |
| 1000000 | sorted | matched-consumption | 36.100 | 56.54 |
| 1000000 | sorted | prepare | 0.001 | 0.01 |
| 1000000 | right-shuffled | deep-materialized | 40.000 | 73.42 |
| 1000000 | right-shuffled | gather-view | 9.687 | 48.82 |
| 1000000 | right-shuffled | matched-consumption | 64.065 | 73.44 |
| 1000000 | right-shuffled | prepare | 0.001 | 0.01 |
| 1000000 | both-shuffled | deep-materialized | 40.201 | 73.42 |
| 1000000 | both-shuffled | gather-view | 9.767 | 48.82 |
| 1000000 | both-shuffled | matched-consumption | 62.536 | 73.43 |
| 1000000 | both-shuffled | prepare | 0.001 | 0.01 |
| 4000000 | sorted | deep-materialized | 58.566 | 194.04 |
| 4000000 | sorted | gather-view | 47.764 | 96.01 |
| 4000000 | sorted | matched-consumption | 150.923 | 194.04 |
| 4000000 | sorted | prepare | 0.001 | 0.01 |
| 4000000 | right-shuffled | deep-materialized | 287.803 | 293.35 |
| 4000000 | right-shuffled | gather-view | 62.585 | 195.16 |
| 4000000 | right-shuffled | matched-consumption | 363.429 | 293.35 |
| 4000000 | right-shuffled | prepare | 0.001 | 0.01 |
| 4000000 | both-shuffled | deep-materialized | 312.246 | 293.35 |
| 4000000 | both-shuffled | gather-view | 63.538 | 195.16 |
| 4000000 | both-shuffled | matched-consumption | 392.298 | 293.35 |
| 4000000 | both-shuffled | prepare | 0.001 | 0.01 |

Exact stable-left output digests, schema, cardinality, checksums, and fixture
fingerprints are in `validation.tsv`. Stage attribution is in `stage-summary.tsv`.
