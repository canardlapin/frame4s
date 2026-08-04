# frame4s matched join endpoint court

Full receipt. Process round 2, sequence position
4. `gather-view` is an internal diagnostic and is not a
dataframe comparator numerator. `deep-materialized` owns physical output buffers;
`matched-consumption` additionally sums all four output columns.

| Rows | Key order | Endpoint | ms/op | Allocation MB/op |
|---:|---|---|---:|---:|
| 1000000 | sorted | deep-materialized | 40.506 | 56.51 |
| 1000000 | sorted | gather-view | 10.278 | 32.01 |
| 1000000 | sorted | matched-consumption | 65.111 | 56.51 |
| 1000000 | sorted | prepare | 0.001 | 0.01 |
| 1000000 | right-shuffled | deep-materialized | 70.140 | 73.45 |
| 1000000 | right-shuffled | gather-view | 10.422 | 48.82 |
| 1000000 | right-shuffled | matched-consumption | 83.860 | 73.45 |
| 1000000 | right-shuffled | prepare | 0.001 | 0.01 |
| 1000000 | both-shuffled | deep-materialized | 69.517 | 73.45 |
| 1000000 | both-shuffled | gather-view | 10.635 | 48.82 |
| 1000000 | both-shuffled | matched-consumption | 108.423 | 73.46 |
| 1000000 | both-shuffled | prepare | 0.001 | 0.01 |
| 4000000 | sorted | deep-materialized | 183.482 | 194.02 |
| 4000000 | sorted | gather-view | 49.962 | 96.01 |
| 4000000 | sorted | matched-consumption | 294.281 | 194.02 |
| 4000000 | sorted | prepare | 0.001 | 0.01 |
| 4000000 | right-shuffled | deep-materialized | 485.608 | 293.32 |
| 4000000 | right-shuffled | gather-view | 73.292 | 195.16 |
| 4000000 | right-shuffled | matched-consumption | 560.936 | 293.33 |
| 4000000 | right-shuffled | prepare | 0.001 | 0.01 |
| 4000000 | both-shuffled | deep-materialized | 484.593 | 293.32 |
| 4000000 | both-shuffled | gather-view | 72.559 | 195.16 |
| 4000000 | both-shuffled | matched-consumption | 629.196 | 293.33 |
| 4000000 | both-shuffled | prepare | 0.001 | 0.01 |

Exact stable-left output digests, schema, cardinality, checksums, and fixture
fingerprints are in `validation.tsv`. Stage attribution is in `stage-summary.tsv`.
