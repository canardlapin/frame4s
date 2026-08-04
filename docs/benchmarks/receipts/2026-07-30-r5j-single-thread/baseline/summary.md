# frame4s one-shot join performance court

Full receipt. This is the same-harness baseline; no optimization is admitted from this receipt.

`execution-only` constructs and blackholes the detached result, then closes it.
`consumed-checksum` additionally walks every output cell in a serial checksum.
Comparator ratios must use the execution-only row; the consumed row remains a
validation and consumption-cost court.

| Workload | Execution-only ms/op | Consumed ms/op | Approx. checksum share | Execution allocation B/op | Same-harness speedup | Allocation ratio |
|---|---:|---:|---:|---:|---:|---:|
| joinOneToOne | 42.846212 | 75.783918 | 43.5% | 65174248 | baseline | baseline |
| joinOneToMany | 36.667629 | 162.289123 | 77.4% | 69173931 | baseline | baseline |
| joinSparse | 25.411547 | 24.101737 | 0.0% | 17953813 | baseline | baseline |
| joinSkewed | 3.035146 | 2.948379 | 0.0% | 12062898 | baseline | baseline |
| semiJoinSparse | 17.284333 | 31.057741 | 44.3% | 17152948 | baseline | baseline |
| antiJoinSparse | 19.445543 | 30.979635 | 37.2% | 24353032 | baseline | baseline |

## Stage attribution

| Workload | Stage | Median ms |
|---|---|---:|
| antiJoinSparse | build | 1.297000 |
| antiJoinSparse | checksum | 8.428958 |
| antiJoinSparse | decode | 0.627417 |
| antiJoinSparse | materialize | 0.036542 |
| antiJoinSparse | probe | 15.605459 |
| joinOneToMany | build | 16.756333 |
| joinOneToMany | checksum | 25.906958 |
| joinOneToMany | decode | 0.622042 |
| joinOneToMany | materialize | 0.048666 |
| joinOneToMany | probe | 14.833375 |
| joinOneToOne | build | 24.483125 |
| joinOneToOne | checksum | 26.168583 |
| joinOneToOne | decode | 0.670667 |
| joinOneToOne | materialize | 0.062167 |
| joinOneToOne | probe | 19.063500 |
| joinSkewed | build | 0.010167 |
| joinSkewed | checksum | 0.030542 |
| joinSkewed | decode | 0.503584 |
| joinSkewed | materialize | 0.014416 |
| joinSkewed | probe | 1.971417 |
| joinSparse | build | 1.127792 |
| joinSparse | checksum | 2.594125 |
| joinSparse | decode | 0.505084 |
| joinSparse | materialize | 0.045458 |
| joinSparse | probe | 14.797667 |
| semiJoinSparse | build | 1.311792 |
| semiJoinSparse | checksum | 0.966541 |
| semiJoinSparse | decode | 0.755084 |
| semiJoinSparse | materialize | 0.046375 |
| semiJoinSparse | probe | 14.645917 |

Individual stage samples are in `raw/stages.tsv`; `stage-summary.tsv`
records median time and median fraction. `metrics.tsv` is the stable input
for a later same-harness candidate receipt. Exact candidate checksums and
cardinalities are in `validation.tsv`; semantic oracle agreement remains
ratified by the small cross-platform court.
