# frame4s matched join endpoint court

Full receipt. Process round 1, sequence position
1. `gather-view` is an internal diagnostic and is not a
dataframe comparator numerator. `deep-materialized` owns physical output buffers;
`matched-consumption` additionally sums all four output columns.

| Rows | Key order | Endpoint | ms/op | Allocation MB/op |
|---:|---|---|---:|---:|
| 1000000 | sorted | deep-materialized | 94.831 | 204.01 |
| 1000000 | sorted | gather-view | 10.662 | 32.01 |
| 1000000 | sorted | matched-consumption | 255.810 | 204.02 |
| 1000000 | sorted | prepare | 0.003 | 0.01 |
| 1000000 | right-shuffled | deep-materialized | 161.460 | 396.99 |
| 1000000 | right-shuffled | gather-view | 29.462 | 48.82 |
| 1000000 | right-shuffled | matched-consumption | 228.749 | 221.01 |
| 1000000 | right-shuffled | prepare | 0.003 | 0.01 |
| 1000000 | both-shuffled | deep-materialized | 112.266 | 221.00 |
| 1000000 | both-shuffled | gather-view | 27.306 | 48.82 |
| 1000000 | both-shuffled | matched-consumption | 253.098 | 221.00 |
| 1000000 | both-shuffled | prepare | 0.002 | 0.01 |
| 4000000 | sorted | deep-materialized | 389.075 | 816.02 |
| 4000000 | sorted | gather-view | 58.479 | 128.01 |
| 4000000 | sorted | matched-consumption | 897.235 | 816.03 |
| 4000000 | sorted | prepare | 0.002 | 0.01 |
| 4000000 | right-shuffled | deep-materialized | 587.693 | 883.36 |
| 4000000 | right-shuffled | gather-view | 152.515 | 195.16 |
| 4000000 | right-shuffled | matched-consumption | 1072.175 | 883.36 |
| 4000000 | right-shuffled | prepare | 0.002 | 0.01 |
| 4000000 | both-shuffled | deep-materialized | 778.983 | 883.36 |
| 4000000 | both-shuffled | gather-view | 136.012 | 195.16 |
| 4000000 | both-shuffled | matched-consumption | 1729.202 | 883.36 |
| 4000000 | both-shuffled | prepare | 0.002 | 0.01 |

Exact stable-left output digests, schema, cardinality, checksums, and fixture
fingerprints are in `validation.tsv`. Stage attribution is in `stage-summary.tsv`.
