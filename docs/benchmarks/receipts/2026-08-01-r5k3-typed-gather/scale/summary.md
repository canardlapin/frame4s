# Join anti-fitting regime court

Full receipt. Every result is checked against a workload-specific analytic oracle.
Strategy is derived from executed profile stages, not inferred from key order.

| Rows | Workload | Strategy | Endpoint | ms/op | Allocation MB/op |
|---:|---|---|---|---:|---:|
| 4000000 | aligned-unique | sorted-merge | deep-materialized | 242.401 | 194.03 |
| 4000000 | aligned-unique | sorted-merge | gather-view | 76.277 | 96.02 |
| 4000000 | aligned-unique | sorted-merge | matched-consumption | 454.431 | 194.04 |
| 4000000 | disjoint-sorted | sorted-merge | deep-materialized | 48.327 | 96.01 |
| 4000000 | disjoint-sorted | sorted-merge | gather-view | 60.947 | 96.02 |
| 4000000 | disjoint-sorted | sorted-merge | matched-consumption | 66.868 | 96.01 |
| 4000000 | fully-shuffled | hash | deep-materialized | 476.502 | 293.34 |
| 4000000 | fully-shuffled | hash | gather-view | 177.143 | 195.17 |
| 4000000 | fully-shuffled | hash | matched-consumption | 806.288 | 293.63 |
| 4000000 | interleaved-gaps | sorted-merge | deep-materialized | 125.210 | 195.79 |
| 4000000 | interleaved-gaps | sorted-merge | gather-view | 94.377 | 163.12 |
| 4000000 | interleaved-gaps | sorted-merge | matched-consumption | 307.698 | 195.80 |
| 4000000 | late-inversion | hash | deep-materialized | 339.737 | 293.34 |
| 4000000 | late-inversion | hash | gather-view | 139.912 | 195.17 |
| 4000000 | late-inversion | hash | matched-consumption | 430.312 | 293.34 |
| 4000000 | multibatch-sorted | sorted-merge | deep-materialized | 257.759 | 194.04 |
| 4000000 | multibatch-sorted | sorted-merge | gather-view | 93.721 | 96.02 |
| 4000000 | multibatch-sorted | sorted-merge | matched-consumption | 331.959 | 194.04 |
| 4000000 | nullable-key | hash | deep-materialized | 222.922 | 294.33 |
| 4000000 | nullable-key | hash | gather-view | 136.562 | 196.17 |
| 4000000 | nullable-key | hash | matched-consumption | 368.154 | 294.32 |
| 4000000 | offset-unique | sorted-merge | deep-materialized | 177.069 | 169.52 |
| 4000000 | offset-unique | sorted-merge | gather-view | 68.467 | 96.02 |
| 4000000 | offset-unique | sorted-merge | matched-consumption | 304.632 | 169.53 |
| 4000000 | sparse-sorted | sorted-merge | deep-materialized | 42.221 | 65.81 |
| 4000000 | sparse-sorted | sorted-merge | gather-view | 32.567 | 56.01 |
| 4000000 | sparse-sorted | sorted-merge | matched-consumption | 59.261 | 65.81 |

`validation.tsv` records exact output rows and checksums, actual dispatch,
cold/warm sorted-detection time, and input fingerprints. `stage-summary.tsv`
retains all executed stages.
