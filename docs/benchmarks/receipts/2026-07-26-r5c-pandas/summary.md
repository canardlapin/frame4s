# Pandas comparison court

Full separate-process Pandas timing receipt.

Pandas runs eagerly in a pinned single-thread Python process. It is not invoked
through JMH, and Python allocation is not compared with JVM GC allocation.

| Workload | Pandas median | Range | frame4s JMH | Pandas/frame4s |
|---|---:|---:|---:|---:|
| `primitiveMaterializedProjection` | 0.100326 ms | 0.098622–0.101286 ms | 0.001507 ms | 66.55x |
| `fusedFilterProjectArithmetic` | 0.282488 ms | 0.274791–0.290855 ms | 0.006545 ms | 43.16x |
| `groupedLowCardinalitySumOnly` | 0.189632 ms | 0.188177–0.192394 ms | 0.008800 ms | 21.55x |
| `groupedLowCardinality` | 0.638812 ms | 0.628291–0.661177 ms | 0.016175 ms | 39.49x |
| `joinOneToOne` | 0.158828 ms | 0.153960–0.172002 ms | 0.104479 ms | 1.52x |
| `joinOneToMany` | 0.209527 ms | 0.197357–0.270892 ms | 0.098302 ms | 2.13x |
| `joinSparse` | 0.191684 ms | 0.185437–0.195805 ms | 0.015529 ms | 12.34x |
| `joinSkewed` | 0.199809 ms | 0.188867–0.205417 ms | 0.094463 ms | 2.12x |
| `distinctLowCardinality` | 0.201588 ms | 0.201224–0.204531 ms | 0.084851 ms | 2.38x |
| `semiJoinSparse` | 0.082359 ms | 0.081686–0.082872 ms | 0.010565 ms | 7.80x |
| `antiJoinSparse` | 0.097292 ms | 0.095472–0.099647 ms | 0.041320 ms | 2.35x |
| `unionAll` | 0.020023 ms | 0.019763–0.020373 ms | 0.012815 ms | 1.56x |

Exact checksum comparisons include primitive materialized projection, fused
filter/project, grouped sum, joins, distinct, semi/anti join, and unionAll.
The four-stat grouped workload is row/schema validated but not raw-bit ranked
because Pandas and frame4s use different floating moment algorithms.

Raw per-sample aggregates are in `raw/timings.csv`; output validation is in
`validation.tsv`; runtime provenance is in `environment.properties`.
