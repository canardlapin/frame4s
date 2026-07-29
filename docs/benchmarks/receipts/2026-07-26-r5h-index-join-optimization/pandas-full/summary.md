# Pandas comparison court

Full separate-process Pandas timing receipt.

Pandas runs eagerly in a pinned single-thread Python process. It is not invoked
through JMH, and Python allocation is not compared with JVM GC allocation.

| Workload | Pandas median | Range | frame4s JMH | Pandas/frame4s |
|---|---:|---:|---:|---:|
| `primitiveMaterializedProjection` | 0.112096 ms | 0.105095–0.139951 ms | n/a | n/a |
| `fusedFilterProjectArithmetic` | 0.406045 ms | 0.286046–0.516251 ms | n/a | n/a |
| `groupedLowCardinalitySumOnly` | 0.222239 ms | 0.195820–0.285558 ms | n/a | n/a |
| `groupedLowCardinality` | 0.706184 ms | 0.663638–0.740288 ms | n/a | n/a |
| `joinOneToOne` | 0.167506 ms | 0.158707–0.213831 ms | 0.050819 ms | 3.30x |
| `joinOneToMany` | 0.209757 ms | 0.197113–0.424082 ms | 0.050764 ms | 4.13x |
| `joinSparse` | 0.205953 ms | 0.194907–0.238682 ms | 0.010034 ms | 20.53x |
| `joinSkewed` | 0.210589 ms | 0.200587–0.249168 ms | 0.050411 ms | 4.18x |
| `distinctLowCardinality` | 0.205709 ms | 0.203009–0.210647 ms | n/a | n/a |
| `semiJoinSparse` | 0.084655 ms | 0.082076–0.086736 ms | n/a | n/a |
| `antiJoinSparse` | 0.099138 ms | 0.096451–0.099663 ms | n/a | n/a |
| `unionAll` | 0.020181 ms | 0.019951–0.020245 ms | n/a | n/a |

Exact checksum comparisons include primitive materialized projection, fused
filter/project, grouped sum, joins, distinct, semi/anti join, and unionAll.
The four-stat grouped workload is row/schema validated but not raw-bit ranked
because Pandas and frame4s use different floating moment algorithms.

Raw per-sample aggregates are in `raw/timings.csv`; output validation is in
`validation.tsv`; runtime provenance is in `environment.properties`.
