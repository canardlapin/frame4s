# frame4s one-shot join performance court

Full receipt. This is the same-harness baseline; no optimization is admitted from this receipt.

`execution-only` constructs and blackholes the detached result, then closes it.
`consumed-checksum` additionally walks every output cell in a serial checksum.
Comparator ratios must use the execution-only row; the consumed row remains a
validation and consumption-cost court.

| Workload | Execution-only ms/op | Consumed ms/op | Approx. checksum share | Execution allocation B/op | Same-harness speedup | Allocation ratio |
|---|---:|---:|---:|---:|---:|---:|
| joinOneToOne | 0.008320 | 0.035536 | 76.6% | 68344 | baseline | baseline |
| joinOneToMany | 0.011831 | 0.036414 | 67.5% | 72360 | baseline | baseline |
| joinSparse | 0.004097 | 0.006800 | 39.8% | 21600 | baseline | baseline |
| joinSkewed | 0.011620 | 0.037484 | 69.0% | 72361 | baseline | baseline |
| semiJoinSparse | 0.003871 | 0.005080 | 23.8% | 20688 | baseline | baseline |
| antiJoinSparse | 0.004140 | 0.013364 | 69.0% | 27912 | baseline | baseline |

## One-to-one stage attribution

| Stage | Median ms |
|---|---:|
| build | 0.151166 |
| checksum | 0.991292 |
| decode | 0.055541 |
| materialize | 0.013916 |
| probe | 0.255625 |

Individual stage samples are in `raw/stages.tsv`; `stage-summary.tsv`
records median time and median fraction. `metrics.tsv` is the stable input
for a later same-harness candidate receipt. Exact candidate checksums and
cardinalities are in `validation.tsv`; semantic oracle agreement remains
ratified by the small cross-platform court.
