# frame4s matched join endpoint court

Full receipt. Process round 1, sequence position
1. `gather-view` is an internal diagnostic and is not a
dataframe comparator numerator. `deep-materialized` owns physical output buffers;
`matched-consumption` additionally sums all four output columns.

| Rows | Key order | Endpoint | ms/op | Allocation MB/op |
|---:|---|---|---:|---:|
| 1000000 | sorted | deep-materialized | 41.002 | 56.51 |
| 1000000 | sorted | gather-view | 10.063 | 32.01 |
| 1000000 | sorted | matched-consumption | 63.313 | 56.51 |
| 1000000 | sorted | prepare | 0.001 | 0.01 |
| 1000000 | right-shuffled | deep-materialized | 81.498 | 73.45 |
| 1000000 | right-shuffled | gather-view | 10.558 | 48.82 |
| 1000000 | right-shuffled | matched-consumption | 84.088 | 73.45 |
| 1000000 | right-shuffled | prepare | 0.001 | 0.01 |
| 1000000 | both-shuffled | deep-materialized | 66.632 | 73.45 |
| 1000000 | both-shuffled | gather-view | 10.866 | 48.82 |
| 1000000 | both-shuffled | matched-consumption | 87.414 | 73.45 |
| 1000000 | both-shuffled | prepare | 0.001 | 0.01 |
| 4000000 | sorted | deep-materialized | 189.752 | 194.02 |
| 4000000 | sorted | gather-view | 49.675 | 96.01 |
| 4000000 | sorted | matched-consumption | 276.802 | 194.02 |
| 4000000 | sorted | prepare | 0.001 | 0.01 |
| 4000000 | right-shuffled | deep-materialized | 533.457 | 293.33 |
| 4000000 | right-shuffled | gather-view | 72.588 | 195.16 |
| 4000000 | right-shuffled | matched-consumption | 546.861 | 293.33 |
| 4000000 | right-shuffled | prepare | 0.001 | 0.01 |
| 4000000 | both-shuffled | deep-materialized | 510.866 | 293.33 |
| 4000000 | both-shuffled | gather-view | 72.761 | 195.16 |
| 4000000 | both-shuffled | matched-consumption | 571.020 | 293.33 |
| 4000000 | both-shuffled | prepare | 0.001 | 0.01 |

Exact stable-left output digests, schema, cardinality, checksums, and fixture
fingerprints are in `validation.tsv`. Stage attribution is in `stage-summary.tsv`.
