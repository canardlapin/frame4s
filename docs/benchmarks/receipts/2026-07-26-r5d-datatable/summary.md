# data.table comparison and index study

Full separate-process data.table timing receipt.

The relational rows disable automatic/reusable indices so repeated timing
does not silently exclude one-time setup. Exact outputs are checked against
the Scala semantic-oracle receipt before measurement.

| Workload | data.table median | Range | frame4s JMH | data.table/frame4s |
|---|---:|---:|---:|---:|
| `primitiveMaterializedProjection` | 0.184082 ms | 0.176758-0.245605 ms | 0.001507 ms | 122.12x |
| `fusedFilterProjectArithmetic` | 0.205566 ms | 0.189941-0.292480 ms | 0.006545 ms | 31.41x |
| `groupedLowCardinalitySumOnly` | 0.277344 ms | 0.261719-0.956055 ms | 0.008800 ms | 31.52x |
| `groupedLowCardinality` | 0.775391 ms | 0.376953-1.654297 ms | 0.016175 ms | 47.94x |
| `joinOneToOne` | 0.544922 ms | 0.419922-0.972656 ms | 0.104479 ms | 5.22x |
| `joinOneToMany` | 0.636719 ms | 0.421875-0.945313 ms | 0.098302 ms | 6.48x |
| `joinSparse` | 0.379883 ms | 0.360352-0.409180 ms | 0.015529 ms | 24.46x |
| `joinSkewed` | 0.410156 ms | 0.390625-0.479492 ms | 0.094463 ms | 4.34x |
| `distinctLowCardinality` | 0.198242 ms | 0.192383-0.208496 ms | 0.084851 ms | 2.34x |
| `semiJoinSparse` | 0.349609 ms | 0.331055-0.363281 ms | 0.010565 ms | 33.09x |
| `antiJoinSparse` | 0.334961 ms | 0.328125-0.354492 ms | 0.041320 ms | 8.11x |
| `unionAll` | 0.030884 ms | 0.029541-0.034058 ms | 0.012815 ms | 2.41x |

The index study is a capability study, not a frame4s win/loss claim. It
uses the same unsorted table for a forced linear equality scan, rebuilds a
secondary index for every cold query, and reuses a prebuilt `setindexv`
index for every warm query.

| Rows | Query | Linear scan | Cold build+lookup | Warm lookup | Warm speedup | Break-even queries | Index bytes |
|---:|---|---:|---:|---:|---:|---:|---:|
| 1000 | single | 0.168945 ms | 0.411133 ms | 0.349609 ms | 0.48x | n/a | 9320 |
| 1000 | batch32 | 0.186523 ms | 0.392578 ms | 0.344727 ms | 0.54x | n/a | 9320 |
| 100000 | single | 0.342773 ms | 4.093750 ms | 0.458984 ms | 0.75x | n/a | 801320 |
| 100000 | batch32 | 0.773438 ms | 4.625000 ms | 0.472656 ms | 1.64x | 13.8 | 801320 |
| 1000000 | single | 1.984375 ms | 18.562500 ms | 1.761719 ms | 1.13x | 75.5 | 8001320 |
| 1000000 | batch32 | 5.765625 ms | 22.375000 ms | 2.085937 ms | 2.76x | 5.5 | 8001320 |

## Interpretation

At the relational fixture size, frame4s is faster on all 12 measured shapes;
the descriptive data.table/frame4s ratios range from 2.34x to 122.12x.
At 1000000 rows, warm index reuse improves a single-target lookup by 1.13x
with a break-even near 75.5 queries; the 32-target batch improves by 2.76x and
breaks even near 5.5 queries.

This supports a separately owned immutable secondary-index capability for
declared repeated workloads. It does not support invisible auto-indexing in
pure `Frame` construction or treating warm indexed lookup as equivalent to a
one-shot scan.

Exact checksum comparisons cover primitive materialization, fused
filter/project, grouped sum, joins, distinct, semi/anti join, and union.
The four-stat floating-moments workload is row/schema validated because
legal reduction algorithms can differ across runtimes.

Raw relational and index timings are under `raw/`. `validation.tsv` and
`index-validation.tsv` carry output evidence; `environment.properties`
records the runtime, dependency, threading, and index-option provenance.
