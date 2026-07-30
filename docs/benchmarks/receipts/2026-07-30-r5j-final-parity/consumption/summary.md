# frame4s one-shot join performance court

Full receipt. This is the same-harness baseline; no optimization is admitted from this receipt.

`execution-only` constructs and blackholes the detached result, then closes it.
`consumed-checksum` additionally walks every output cell in a serial checksum.
Comparator ratios must use the execution-only row; the consumed row remains a
validation and consumption-cost court.

| Workload | Execution-only ms/op | Consumed ms/op | Approx. checksum share | Execution allocation B/op | Same-harness speedup | Allocation ratio |
|---|---:|---:|---:|---:|---:|---:|
| joinOneToOne | 9.372867 | 33.887958 | 72.3% | 32007811 | baseline | baseline |
| joinOneToMany | 13.109926 | 37.913670 | 65.4% | 32007958 | baseline | baseline |
| joinSparse | 5.850177 | 8.459094 | 30.8% | 14007616 | baseline | baseline |
| joinSkewed | 0.537480 | 0.565674 | 5.0% | 12066074 | baseline | baseline |
| semiJoinSparse | 5.915535 | 6.951168 | 14.9% | 13607528 | baseline | baseline |
| antiJoinSparse | 6.349099 | 13.976517 | 54.6% | 17207564 | baseline | baseline |

## Stage attribution

| Workload | Stage | Median ms |
|---|---|---:|
| antiJoinSparse | checksum | 7.604208 |
| antiJoinSparse | decode | 0.290000 |
| antiJoinSparse | detect-sorted | 2.061084 |
| antiJoinSparse | materialize | 0.016750 |
| antiJoinSparse | merge-probe | 4.239875 |
| joinOneToMany | checksum | 24.240083 |
| joinOneToMany | decode | 0.918667 |
| joinOneToMany | detect-sorted | 3.805916 |
| joinOneToMany | materialize | 0.048250 |
| joinOneToMany | merge-probe | 9.427709 |
| joinOneToOne | checksum | 24.384417 |
| joinOneToOne | decode | 1.195041 |
| joinOneToOne | detect-sorted | 3.781875 |
| joinOneToOne | materialize | 0.046958 |
| joinOneToOne | merge-probe | 5.687250 |
| joinSkewed | build | 0.056458 |
| joinSkewed | checksum | 0.032041 |
| joinSkewed | decode | 0.253750 |
| joinSkewed | detect-sorted | 0.001125 |
| joinSkewed | materialize | 0.014083 |
| joinSkewed | parallel-probe | 0.471542 |
| joinSparse | checksum | 2.459000 |
| joinSparse | decode | 0.519333 |
| joinSparse | detect-sorted | 2.052416 |
| joinSparse | materialize | 0.015208 |
| joinSparse | merge-probe | 3.635500 |
| semiJoinSparse | checksum | 0.859833 |
| semiJoinSparse | decode | 0.275125 |
| semiJoinSparse | detect-sorted | 2.053542 |
| semiJoinSparse | materialize | 0.009458 |
| semiJoinSparse | merge-probe | 3.779625 |

Individual stage samples are in `raw/stages.tsv`; `stage-summary.tsv`
records median time and median fraction. `metrics.tsv` is the stable input
for a later same-harness candidate receipt. Exact candidate checksums and
cardinalities are in `validation.tsv`; semantic oracle agreement remains
ratified by the small cross-platform court.
