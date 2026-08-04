# Pandas comparison court

Full separate-process Pandas timing receipt.

Pandas runs eagerly in a pinned single-thread Python process. It is not invoked
through JMH, and Python allocation is not compared with JVM GC allocation.

| Workload | Pandas median | Range | frame4s JMH | frame4s path | Pandas/frame4s |
|---|---:|---:|---:|---|---:|
| `primitiveMaterializedProjection` | 0.101946 ms | 0.099209–0.105341 ms | n/a | n/a | n/a |
| `fusedFilterProjectArithmetic` | 0.284635 ms | 0.277583–0.330474 ms | n/a | n/a | n/a |
| `groupedLowCardinalitySumOnly` | 0.183941 ms | 0.183141–0.187598 ms | n/a | n/a | n/a |
| `groupedLowCardinality` | 0.637037 ms | 0.623848–0.706133 ms | n/a | n/a | n/a |
| `joinOneToOne` | 0.154507 ms | 0.153274–0.157855 ms | 0.008320 ms | detached-result-construction | 18.57x |
| `joinOneToMany` | 0.190875 ms | 0.186545–0.207841 ms | 0.011831 ms | detached-result-construction | 16.13x |
| `joinSparse` | 0.180023 ms | 0.176439–0.186293 ms | 0.004097 ms | detached-result-construction | 43.94x |
| `joinSkewed` | 0.194367 ms | 0.188288–0.197495 ms | 0.011620 ms | detached-result-construction | 16.73x |
| `distinctLowCardinality` | 0.206609 ms | 0.203444–0.211605 ms | n/a | n/a | n/a |
| `semiJoinSparse` | 0.082380 ms | 0.081145–0.091294 ms | 0.003871 ms | detached-result-construction | 21.28x |
| `antiJoinSparse` | 0.096777 ms | 0.095111–0.099493 ms | 0.004140 ms | detached-result-construction | 23.38x |
| `unionAll` | 0.020028 ms | 0.019882–0.020586 ms | n/a | n/a | n/a |

Exact checksum comparisons include primitive materialized projection, fused
filter/project, grouped sum, joins, distinct, semi/anti join, and unionAll.
The four-stat grouped workload is row/schema validated but not raw-bit ranked
because Pandas and frame4s use different floating moment algorithms.

Raw per-sample aggregates are in `raw/timings.csv`; output validation is in
`validation.tsv`; runtime provenance is in `environment.properties`.
