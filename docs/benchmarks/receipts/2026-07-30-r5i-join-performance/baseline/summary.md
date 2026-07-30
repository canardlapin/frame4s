# frame4s one-shot join performance court

Full receipt. This is the same-harness baseline; no optimization is admitted from this receipt.

`execution-only` constructs and blackholes the detached result, then closes it.
`consumed-checksum` additionally walks every output cell in a serial checksum.
Comparator ratios must use the execution-only row; the consumed row remains a
validation and consumption-cost court.

| Workload | Execution-only ms/op | Consumed ms/op | Approx. checksum share | Execution allocation B/op | Same-harness speedup | Allocation ratio |
|---|---:|---:|---:|---:|---:|---:|
| joinOneToOne | 129.681024 | 157.427976 | 17.6% | 110732193 | baseline | baseline |
| joinOneToMany | 89.808539 | 134.203046 | 33.1% | 110730965 | baseline | baseline |
| joinSparse | 23.391692 | 45.876516 | 49.0% | 23348209 | baseline | baseline |
| joinSkewed | 8.482616 | 7.395651 | 0.0% | 12104391 | baseline | baseline |
| semiJoinSparse | 21.569361 | 25.622585 | 15.8% | 20450629 | baseline | baseline |
| antiJoinSparse | 56.809599 | 50.449826 | 0.0% | 41531591 | baseline | baseline |

## One-to-one stage attribution

| Stage | Median ms |
|---|---:|
| build | 20.154250 |
| checksum | 28.599000 |
| decode | 1.305792 |
| materialize | 0.605500 |
| probe | 89.925750 |

Individual stage samples are in `raw/stages.tsv`; `stage-summary.tsv`
records median time and median fraction. `metrics.tsv` is the stable input
for a later same-harness candidate receipt. Exact candidate checksums and
cardinalities are in `validation.tsv`; semantic oracle agreement remains
ratified by the small cross-platform court.
