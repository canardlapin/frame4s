# data.table comparison and index study

Full separate-process data.table timing receipt.

The relational rows disable automatic/reusable indices so repeated timing
does not silently exclude one-time setup. Exact outputs are checked against
the Scala semantic-oracle receipt before measurement.

| Workload | data.table median | Range | frame4s JMH | data.table/frame4s |
|---|---:|---:|---:|---:|
| `primitiveMaterializedProjection` | 0.173828 ms | 0.167969-0.181641 ms | n/a | n/a |
| `fusedFilterProjectArithmetic` | 0.186523 ms | 0.178223-0.198730 ms | n/a | n/a |
| `groupedLowCardinalitySumOnly` | 0.252930 ms | 0.243164-0.261719 ms | n/a | n/a |
| `groupedLowCardinality` | 0.357422 ms | 0.336914-0.376953 ms | n/a | n/a |
| `joinOneToOne` | 0.425781 ms | 0.411133-0.437500 ms | 0.056562 ms | 7.53x |
| `joinOneToMany` | 0.439453 ms | 0.413086-0.467773 ms | 0.051471 ms | 8.54x |
| `joinSparse` | 0.394531 ms | 0.378906-0.405273 ms | 0.014902 ms | 26.48x |
| `joinSkewed` | 0.422852 ms | 0.399414-0.584961 ms | 0.067969 ms | 6.22x |
| `distinctLowCardinality` | 0.217773 ms | 0.191406-0.312500 ms | n/a | n/a |
| `semiJoinSparse` | 0.368164 ms | 0.345703-0.392578 ms | n/a | n/a |
| `antiJoinSparse` | 0.351562 ms | 0.340820-0.390625 ms | n/a | n/a |
| `unionAll` | 0.032349 ms | 0.030396-0.032959 ms | n/a | n/a |

The index study is a capability study, not a frame4s win/loss claim. It
uses the same unsorted table for a forced linear equality scan, rebuilds a
secondary index for every cold query, and reuses a prebuilt `setindexv`
index for every warm query.

| Rows | Query | Linear scan | Cold build+lookup | Warm lookup | Warm speedup | Break-even queries | Index bytes |
|---:|---|---:|---:|---:|---:|---:|---:|
| 1000 | single | 0.179199 ms | 0.413086 ms | 0.340820 ms | 0.53x | n/a | 9320 |
| 1000 | batch32 | 0.188477 ms | 0.395508 ms | 0.346680 ms | 0.54x | n/a | 9320 |
| 100000 | single | 0.364258 ms | 4.937500 ms | 0.542969 ms | 0.67x | n/a | 801320 |
| 100000 | batch32 | 0.925781 ms | 4.656250 ms | 0.456055 ms | 2.03x | 8.9 | 801320 |
| 1000000 | single | 1.906250 ms | 18.250000 ms | 1.652344 ms | 1.15x | 65.4 | 8001320 |
| 1000000 | batch32 | 5.687500 ms | 18.187500 ms | 1.667969 ms | 3.41x | 4.1 | 8001320 |

## Interpretation

The supplied current frame4s JMH receipt contains the four join shapes. frame4s
is faster on all four, with descriptive data.table/frame4s ratios from 6.22x
to 26.48x. The other data.table rows remain validated external timings, but
this join-focused receipt does not rank them against older frame4s points.
At 1000000 rows, the linear-scan/warm-index ratio is 1.15x for one target with break-even 65.4 queries; for the 32-target batch it is 3.41x with break-even 4.1 queries.
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
