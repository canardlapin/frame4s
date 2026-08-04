# Join anti-fitting regime court

Full receipt. Every result is checked against a workload-specific analytic oracle.
Strategy is derived from executed profile stages, not inferred from key order.

| Rows | Workload | Strategy | Endpoint | ms/op | Allocation MB/op |
|---:|---|---|---|---:|---:|
| 4000000 | aligned-unique | sorted-merge | deep-materialized | 654.683 | 816.04 |
| 4000000 | aligned-unique | sorted-merge | gather-view | 86.312 | 128.02 |
| 4000000 | aligned-unique | sorted-merge | matched-consumption | 590.878 | 816.04 |
| 4000000 | disjoint-sorted | sorted-merge | deep-materialized | 86.476 | 128.02 |
| 4000000 | disjoint-sorted | sorted-merge | gather-view | 158.846 | 128.02 |
| 4000000 | disjoint-sorted | sorted-merge | matched-consumption | 99.261 | 128.02 |
| 4000000 | fully-shuffled | hash | deep-materialized | 778.301 | 883.37 |
| 4000000 | fully-shuffled | hash | gather-view | 185.369 | 195.17 |
| 4000000 | fully-shuffled | hash | matched-consumption | 1025.158 | 883.37 |
| 4000000 | interleaved-gaps | sorted-merge | deep-materialized | 267.302 | 357.35 |
| 4000000 | interleaved-gaps | sorted-merge | gather-view | 97.967 | 128.02 |
| 4000000 | interleaved-gaps | sorted-merge | matched-consumption | 311.999 | 357.36 |
| 4000000 | late-inversion | hash | deep-materialized | 439.780 | 883.37 |
| 4000000 | late-inversion | hash | gather-view | 150.828 | 195.17 |
| 4000000 | late-inversion | hash | matched-consumption | 647.650 | 883.37 |
| 4000000 | multibatch-sorted | sorted-merge | deep-materialized | 498.346 | 848.05 |
| 4000000 | multibatch-sorted | sorted-merge | gather-view | 168.422 | 160.03 |
| 4000000 | multibatch-sorted | sorted-merge | matched-consumption | 756.351 | 848.05 |
| 4000000 | nullable-key | hash | deep-materialized | 452.822 | 884.38 |
| 4000000 | nullable-key | hash | gather-view | 260.871 | 196.18 |
| 4000000 | nullable-key | hash | matched-consumption | 955.142 | 884.37 |
| 4000000 | offset-unique | sorted-merge | deep-materialized | 402.481 | 644.03 |
| 4000000 | offset-unique | sorted-merge | gather-view | 86.485 | 128.02 |
| 4000000 | offset-unique | sorted-merge | matched-consumption | 559.026 | 644.04 |
| 4000000 | sparse-sorted | sorted-merge | deep-materialized | 78.234 | 124.82 |
| 4000000 | sparse-sorted | sorted-merge | gather-view | 90.526 | 56.01 |
| 4000000 | sparse-sorted | sorted-merge | matched-consumption | 105.616 | 124.82 |

`validation.tsv` records exact output rows and checksums, actual dispatch,
cold/warm sorted-detection time, and input fingerprints. `stage-summary.tsv`
retains all executed stages.
