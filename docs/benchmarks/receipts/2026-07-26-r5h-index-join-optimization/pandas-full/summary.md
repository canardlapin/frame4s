# Pandas comparison court

Full separate-process Pandas timing receipt.

Pandas runs eagerly in a pinned single-thread Python process. It is not invoked
through JMH, and Python allocation is not compared with JVM GC allocation.

| Workload | Pandas median | Range | frame4s JMH | Pandas/frame4s |
|---|---:|---:|---:|---:|
| `primitiveMaterializedProjection` | 0.101517 ms | 0.100269–0.102539 ms | n/a | n/a |
| `fusedFilterProjectArithmetic` | 0.284184 ms | 0.280374–0.286855 ms | n/a | n/a |
| `groupedLowCardinalitySumOnly` | 0.186845 ms | 0.185384–0.188701 ms | n/a | n/a |
| `groupedLowCardinality` | 0.627466 ms | 0.612314–0.643969 ms | n/a | n/a |
| `joinOneToOne` | 0.154100 ms | 0.151671–0.155322 ms | 0.056562 ms | 2.72x |
| `joinOneToMany` | 0.192051 ms | 0.188286–0.196667 ms | 0.051471 ms | 3.73x |
| `joinSparse` | 0.180704 ms | 0.176118–0.181737 ms | 0.014902 ms | 12.13x |
| `joinSkewed` | 0.191589 ms | 0.187468–0.193788 ms | 0.067969 ms | 2.82x |
| `distinctLowCardinality` | 0.204144 ms | 0.199511–0.205866 ms | n/a | n/a |
| `semiJoinSparse` | 0.082511 ms | 0.081823–0.083830 ms | n/a | n/a |
| `antiJoinSparse` | 0.097541 ms | 0.096359–0.099380 ms | n/a | n/a |
| `unionAll` | 0.020298 ms | 0.020157–0.020527 ms | n/a | n/a |

Exact checksum comparisons include primitive materialized projection, fused
filter/project, grouped sum, joins, distinct, semi/anti join, and unionAll.
The four-stat grouped workload is row/schema validated but not raw-bit ranked
because Pandas and frame4s use different floating moment algorithms.

Raw per-sample aggregates are in `raw/timings.csv`; output validation is in
`validation.tsv`; runtime provenance is in `environment.properties`.
