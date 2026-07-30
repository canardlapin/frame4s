# frame4s matched join endpoint court

Full receipt. Process round 3, sequence position
3. `gather-view` is an internal diagnostic and is not a
dataframe comparator numerator. `deep-materialized` owns physical output buffers;
`matched-consumption` additionally sums all four output columns.

| Rows | Key order | Endpoint | ms/op | Allocation MB/op |
|---:|---|---|---:|---:|
| 1000000 | sorted | deep-materialized | 119.920 | 204.02 |
| 1000000 | sorted | gather-view | 9.983 | 32.01 |
| 1000000 | sorted | matched-consumption | 168.826 | 204.02 |
| 1000000 | sorted | prepare | 0.002 | 0.01 |
| 1000000 | right-shuffled | deep-materialized | 159.781 | 221.00 |
| 1000000 | right-shuffled | gather-view | 22.465 | 48.82 |
| 1000000 | right-shuffled | matched-consumption | 243.509 | 221.00 |
| 1000000 | right-shuffled | prepare | 0.002 | 0.01 |
| 1000000 | both-shuffled | deep-materialized | 204.626 | 221.01 |
| 1000000 | both-shuffled | gather-view | 28.848 | 48.82 |
| 1000000 | both-shuffled | matched-consumption | 160.789 | 221.00 |
| 1000000 | both-shuffled | prepare | 0.002 | 0.01 |
| 4000000 | sorted | deep-materialized | 454.436 | 816.03 |
| 4000000 | sorted | gather-view | 42.799 | 128.01 |
| 4000000 | sorted | matched-consumption | 773.633 | 816.03 |
| 4000000 | sorted | prepare | 0.002 | 0.01 |
| 4000000 | right-shuffled | deep-materialized | 962.130 | 883.36 |
| 4000000 | right-shuffled | gather-view | 149.687 | 195.16 |
| 4000000 | right-shuffled | matched-consumption | 1195.327 | 883.36 |
| 4000000 | right-shuffled | prepare | 0.002 | 0.01 |
| 4000000 | both-shuffled | deep-materialized | 1005.460 | 883.36 |
| 4000000 | both-shuffled | gather-view | 106.628 | 195.16 |
| 4000000 | both-shuffled | matched-consumption | 1498.104 | 883.36 |
| 4000000 | both-shuffled | prepare | 0.003 | 0.01 |

Exact stable-left output digests, schema, cardinality, checksums, and fixture
fingerprints are in `validation.tsv`. Stage attribution is in `stage-summary.tsv`.
