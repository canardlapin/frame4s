# frame4s one-shot join performance court

Full receipt. Decision: the candidate passes its >=1.15x one-to-one speed gate; allocation passes its absolute and no-regression gates. Whether a parallel probe is admitted is stated by the candidate receipt that enables it.

`execution-only` constructs and blackholes the detached result, then closes it.
`consumed-checksum` additionally walks every output cell in a serial checksum.
Comparator ratios must use the execution-only row; the consumed row remains a
validation and consumption-cost court.

| Workload | Execution-only ms/op | Consumed ms/op | Approx. checksum share | Execution allocation B/op | Same-harness speedup | Allocation ratio |
|---|---:|---:|---:|---:|---:|---:|
| joinOneToOne | 30.999150 | 71.167264 | 56.4% | 65173730 | 1.38x | 1.000 |
| joinOneToMany | 43.231228 | 76.615338 | 43.6% | 69174172 | 0.85x | 1.000 |
| joinSparse | 16.856597 | 24.302358 | 30.6% | 17953057 | 1.51x | 1.000 |
| joinSkewed | 2.858343 | 3.049166 | 6.3% | 12062875 | 1.06x | 1.000 |
| semiJoinSparse | 76.302231 | 36.511231 | 0.0% | 17155251 | 0.23x | 1.000 |
| antiJoinSparse | 17.185640 | 27.446377 | 37.4% | 24352982 | 1.13x | 1.000 |

## Stage attribution

| Workload | Stage | Median ms |
|---|---|---:|
| antiJoinSparse | build | 1.160125 |
| antiJoinSparse | checksum | 7.959750 |
| antiJoinSparse | decode | 0.737041 |
| antiJoinSparse | materialize | 0.040583 |
| antiJoinSparse | probe | 18.015708 |
| joinOneToMany | build | 14.890500 |
| joinOneToMany | checksum | 25.410750 |
| joinOneToMany | decode | 0.593041 |
| joinOneToMany | materialize | 0.062833 |
| joinOneToMany | probe | 16.381250 |
| joinOneToOne | build | 17.906500 |
| joinOneToOne | checksum | 25.815834 |
| joinOneToOne | decode | 1.043208 |
| joinOneToOne | materialize | 0.063666 |
| joinOneToOne | probe | 21.875583 |
| joinSkewed | build | 0.009167 |
| joinSkewed | checksum | 0.031875 |
| joinSkewed | decode | 0.254583 |
| joinSkewed | materialize | 0.011084 |
| joinSkewed | probe | 2.644208 |
| joinSparse | build | 1.208000 |
| joinSparse | checksum | 2.583125 |
| joinSparse | decode | 0.723417 |
| joinSparse | materialize | 0.046917 |
| joinSparse | probe | 16.777542 |
| semiJoinSparse | build | 0.979333 |
| semiJoinSparse | checksum | 0.911334 |
| semiJoinSparse | decode | 0.342000 |
| semiJoinSparse | materialize | 0.038292 |
| semiJoinSparse | probe | 16.216666 |

Individual stage samples are in `raw/stages.tsv`; `stage-summary.tsv`
records median time and median fraction. `metrics.tsv` is the stable input
for a later same-harness candidate receipt. Exact candidate checksums and
cardinalities are in `validation.tsv`; semantic oracle agreement remains
ratified by the small cross-platform court.
