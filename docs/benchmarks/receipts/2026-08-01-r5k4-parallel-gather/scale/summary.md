# Join anti-fitting regime court

Full receipt. Every result is checked against a workload-specific analytic oracle.
Strategy is derived from executed profile stages, not inferred from key order.

| Rows | Workload | Strategy | Endpoint | ms/op | Allocation MB/op |
|---:|---|---|---|---:|---:|
| 4000000 | aligned-unique | sorted-merge | deep-materialized | 81.380 | 194.04 |
| 4000000 | aligned-unique | sorted-merge | gather-view | 56.406 | 96.01 |
| 4000000 | aligned-unique | sorted-merge | matched-consumption | 165.778 | 194.05 |
| 4000000 | disjoint-sorted | sorted-merge | deep-materialized | 36.504 | 96.01 |
| 4000000 | disjoint-sorted | sorted-merge | gather-view | 35.102 | 96.01 |
| 4000000 | disjoint-sorted | sorted-merge | matched-consumption | 38.312 | 96.01 |
| 4000000 | fully-shuffled | hash | deep-materialized | 306.819 | 293.36 |
| 4000000 | fully-shuffled | hash | gather-view | 79.592 | 195.17 |
| 4000000 | fully-shuffled | hash | matched-consumption | 406.694 | 293.37 |
| 4000000 | interleaved-gaps | sorted-merge | deep-materialized | 62.103 | 195.82 |
| 4000000 | interleaved-gaps | sorted-merge | gather-view | 51.705 | 163.12 |
| 4000000 | interleaved-gaps | sorted-merge | matched-consumption | 92.808 | 195.82 |
| 4000000 | late-inversion | hash | deep-materialized | 165.832 | 293.35 |
| 4000000 | late-inversion | hash | gather-view | 85.842 | 195.17 |
| 4000000 | late-inversion | hash | matched-consumption | 256.581 | 293.35 |
| 4000000 | multibatch-sorted | sorted-merge | deep-materialized | 72.558 | 194.05 |
| 4000000 | multibatch-sorted | sorted-merge | gather-view | 59.524 | 96.02 |
| 4000000 | multibatch-sorted | sorted-merge | matched-consumption | 172.396 | 194.05 |
| 4000000 | nullable-key | hash | deep-materialized | 188.853 | 294.35 |
| 4000000 | nullable-key | hash | gather-view | 91.903 | 196.17 |
| 4000000 | nullable-key | hash | matched-consumption | 273.760 | 294.36 |
| 4000000 | offset-unique | sorted-merge | deep-materialized | 69.832 | 169.54 |
| 4000000 | offset-unique | sorted-merge | gather-view | 54.961 | 96.01 |
| 4000000 | offset-unique | sorted-merge | matched-consumption | 136.474 | 169.54 |
| 4000000 | sparse-sorted | sorted-merge | deep-materialized | 29.769 | 65.82 |
| 4000000 | sparse-sorted | sorted-merge | gather-view | 26.259 | 56.01 |
| 4000000 | sparse-sorted | sorted-merge | matched-consumption | 39.470 | 65.82 |

`validation.tsv` records exact output rows and checksums, actual dispatch,
cold/warm sorted-detection time, and input fingerprints. `stage-summary.tsv`
retains all executed stages.
