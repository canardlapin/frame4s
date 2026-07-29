# data.table comparison and index study

Full separate-process data.table timing receipt.

The relational rows disable automatic/reusable indices so repeated timing
does not silently exclude one-time setup. Exact outputs are checked against
the Scala semantic-oracle receipt before measurement.

| Workload | data.table median | Range | frame4s JMH | data.table/frame4s |
|---|---:|---:|---:|---:|
| `primitiveMaterializedProjection` | 0.174316 ms | 0.166504-0.177246 ms | n/a | n/a |
| `fusedFilterProjectArithmetic` | 0.184570 ms | 0.169922-0.196777 ms | n/a | n/a |
| `groupedLowCardinalitySumOnly` | 0.245117 ms | 0.239258-0.259766 ms | n/a | n/a |
| `groupedLowCardinality` | 0.348633 ms | 0.341797-0.369141 ms | n/a | n/a |
| `joinOneToOne` | 0.420898 ms | 0.409180-0.431641 ms | 0.050819 ms | 8.28x |
| `joinOneToMany` | 0.405273 ms | 0.402344-0.426758 ms | 0.050764 ms | 7.98x |
| `joinSparse` | 0.386719 ms | 0.371094-0.399414 ms | 0.010034 ms | 38.54x |
| `joinSkewed` | 0.407227 ms | 0.382812-0.420898 ms | 0.050411 ms | 8.08x |
| `distinctLowCardinality` | 0.205078 ms | 0.198242-0.222656 ms | n/a | n/a |
| `semiJoinSparse` | 0.360352 ms | 0.344727-0.378906 ms | n/a | n/a |
| `antiJoinSparse` | 0.339844 ms | 0.335938-0.357422 ms | n/a | n/a |
| `unionAll` | 0.032837 ms | 0.030396-0.037842 ms | n/a | n/a |

The index study is a capability study, not a frame4s win/loss claim. It
uses the same unsorted table for a forced linear equality scan, rebuilds a
secondary index for every cold query, and reuses a prebuilt `setindexv`
index for every warm query.

| Rows | Query | Linear scan | Cold build+lookup | Warm lookup | Warm speedup | Break-even queries | Index bytes |
|---:|---|---:|---:|---:|---:|---:|---:|
| 1000 | single | 0.177246 ms | 0.395508 ms | 0.349609 ms | 0.51x | n/a | 9320 |
| 1000 | batch32 | 0.188477 ms | 0.383789 ms | 0.344727 ms | 0.55x | n/a | 9320 |
| 100000 | single | 0.348633 ms | 4.609375 ms | 0.465820 ms | 0.75x | n/a | 801320 |
| 100000 | batch32 | 0.800781 ms | 4.609375 ms | 0.473633 ms | 1.69x | 12.6 | 801320 |
| 1000000 | single | 2.039063 ms | 19.375000 ms | 1.644531 ms | 1.24x | 44.9 | 8001320 |
| 1000000 | batch32 | 5.515625 ms | 18.312500 ms | 1.601563 ms | 3.44x | 4.3 | 8001320 |

## Interpretation

At the relational fixture size, frame4s is faster on all 12 measured shapes; the descriptive data.table/frame4s ratios range from 7.98x to 38.54x.
At 1000000 rows, the linear-scan/warm-index ratio is 1.24x for one target with break-even 44.9 queries; for the 32-target batch it is 3.44x with break-even 4.3 queries.
This supports a separately owned immutable secondary-index capability for
declared repeated workloads. It does not support invisible auto-indexing in
pure `Frame` construction or treating warm indexed lookup as equivalent to
a one-shot scan.


Exact checksum comparisons cover primitive materialization, fused
filter/project, grouped sum, joins, distinct, semi/anti join, and union.
The four-stat floating-moments workload is row/schema validated because
legal reduction algorithms can differ across runtimes.

Raw relational and index timings are under `raw/`. `validation.tsv` and
`index-validation.tsv` carry output evidence; `environment.properties`
records the runtime, dependency, threading, and index-option provenance.
