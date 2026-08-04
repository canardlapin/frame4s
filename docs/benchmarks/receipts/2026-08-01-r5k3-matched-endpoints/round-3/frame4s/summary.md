# frame4s matched join endpoint court

Full receipt. Process round 3, sequence position
3. `gather-view` is an internal diagnostic and is not a
dataframe comparator numerator. `deep-materialized` owns physical output buffers;
`matched-consumption` additionally sums all four output columns.

| Rows | Key order | Endpoint | ms/op | Allocation MB/op |
|---:|---|---|---:|---:|
| 1000000 | sorted | deep-materialized | 38.866 | 56.51 |
| 1000000 | sorted | gather-view | 9.901 | 32.01 |
| 1000000 | sorted | matched-consumption | 62.439 | 56.51 |
| 1000000 | sorted | prepare | 0.001 | 0.01 |
| 1000000 | right-shuffled | deep-materialized | 49.693 | 73.44 |
| 1000000 | right-shuffled | gather-view | 9.575 | 48.82 |
| 1000000 | right-shuffled | matched-consumption | 73.223 | 73.45 |
| 1000000 | right-shuffled | prepare | 0.002 | 0.01 |
| 1000000 | both-shuffled | deep-materialized | 50.425 | 73.44 |
| 1000000 | both-shuffled | gather-view | 9.671 | 48.82 |
| 1000000 | both-shuffled | matched-consumption | 75.584 | 73.45 |
| 1000000 | both-shuffled | prepare | 0.002 | 0.01 |
| 4000000 | sorted | deep-materialized | 180.470 | 194.02 |
| 4000000 | sorted | gather-view | 65.065 | 96.01 |
| 4000000 | sorted | matched-consumption | 273.492 | 194.02 |
| 4000000 | sorted | prepare | 0.002 | 0.01 |
| 4000000 | right-shuffled | deep-materialized | 374.780 | 293.32 |
| 4000000 | right-shuffled | gather-view | 65.933 | 195.16 |
| 4000000 | right-shuffled | matched-consumption | 492.564 | 293.32 |
| 4000000 | right-shuffled | prepare | 0.002 | 0.01 |
| 4000000 | both-shuffled | deep-materialized | 410.996 | 293.32 |
| 4000000 | both-shuffled | gather-view | 63.997 | 195.16 |
| 4000000 | both-shuffled | matched-consumption | 509.367 | 293.33 |
| 4000000 | both-shuffled | prepare | 0.003 | 0.01 |

Exact stable-left output digests, schema, cardinality, checksums, and fixture
fingerprints are in `validation.tsv`. Stage attribution is in `stage-summary.tsv`.
