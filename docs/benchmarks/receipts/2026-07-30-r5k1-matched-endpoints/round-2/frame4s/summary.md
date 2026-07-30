# frame4s matched join endpoint court

Full receipt. Process round 2, sequence position
4. `gather-view` is an internal diagnostic and is not a
dataframe comparator numerator. `deep-materialized` owns physical output buffers;
`matched-consumption` additionally sums all four output columns.

| Rows | Key order | Endpoint | ms/op | Allocation MB/op |
|---:|---|---|---:|---:|
| 1000000 | sorted | deep-materialized | 317.060 | 204.02 |
| 1000000 | sorted | gather-view | 9.655 | 32.01 |
| 1000000 | sorted | matched-consumption | 288.594 | 204.02 |
| 1000000 | sorted | prepare | 0.001 | 0.01 |
| 1000000 | right-shuffled | deep-materialized | 100.653 | 221.00 |
| 1000000 | right-shuffled | gather-view | 12.410 | 48.81 |
| 1000000 | right-shuffled | matched-consumption | 119.540 | 221.00 |
| 1000000 | right-shuffled | prepare | 0.002 | 0.01 |
| 1000000 | both-shuffled | deep-materialized | 86.967 | 221.00 |
| 1000000 | both-shuffled | gather-view | 12.832 | 48.81 |
| 1000000 | both-shuffled | matched-consumption | 111.783 | 220.99 |
| 1000000 | both-shuffled | prepare | 0.002 | 0.01 |
| 4000000 | sorted | deep-materialized | 482.483 | 1520.02 |
| 4000000 | sorted | gather-view | 37.440 | 128.01 |
| 4000000 | sorted | matched-consumption | 659.224 | 816.02 |
| 4000000 | sorted | prepare | 0.001 | 0.01 |
| 4000000 | right-shuffled | deep-materialized | 531.776 | 883.36 |
| 4000000 | right-shuffled | gather-view | 81.246 | 195.16 |
| 4000000 | right-shuffled | matched-consumption | 726.443 | 883.36 |
| 4000000 | right-shuffled | prepare | 0.001 | 0.01 |
| 4000000 | both-shuffled | deep-materialized | 499.493 | 883.35 |
| 4000000 | both-shuffled | gather-view | 80.546 | 195.16 |
| 4000000 | both-shuffled | matched-consumption | 660.885 | 883.36 |
| 4000000 | both-shuffled | prepare | 0.002 | 0.01 |

Exact stable-left output digests, schema, cardinality, checksums, and fixture
fingerprints are in `validation.tsv`. Stage attribution is in `stage-summary.tsv`.
