# frame4s one-shot join performance court

Full receipt. Decision: the candidate passes its >=1.15x one-to-one speed gate; allocation passes its absolute and no-regression gates. Whether a parallel probe is admitted is stated by the candidate receipt that enables it.

`execution-only` constructs and blackholes the detached result, then closes it.
`consumed-checksum` additionally walks every output cell in a serial checksum.
Comparator ratios must use the execution-only row; the consumed row remains a
validation and consumption-cost court.

| Workload | Execution-only ms/op | Consumed ms/op | Approx. checksum share | Execution allocation B/op | Same-harness speedup | Allocation ratio |
|---|---:|---:|---:|---:|---:|---:|
| joinOneToOne | 27.291118 | 54.479608 | 49.9% | 48784877 | 1.57x | 0.749 |
| joinOneToMany | 44.815211 | 74.589242 | 39.9% | 61174027 | 0.82x | 0.884 |
| joinSparse | 19.511075 | 19.862518 | 1.8% | 16104564 | 1.30x | 0.897 |
| joinSkewed | 1.433335 | 1.448157 | 1.0% | 12053792 | 2.12x | 0.999 |
| semiJoinSparse | 15.165244 | 14.298190 | 0.0% | 15704255 | 1.14x | 0.916 |
| antiJoinSparse | 16.737746 | 23.739986 | 29.5% | 19304368 | 1.16x | 0.793 |

## Stage attribution

| Workload | Stage | Median ms |
|---|---|---:|
| antiJoinSparse | build | 1.309125 |
| antiJoinSparse | checksum | 7.807458 |
| antiJoinSparse | decode | 0.757459 |
| antiJoinSparse | materialize | 0.042416 |
| antiJoinSparse | probe | 11.151500 |
| joinOneToMany | build | 22.251375 |
| joinOneToMany | checksum | 24.998125 |
| joinOneToMany | decode | 0.674208 |
| joinOneToMany | materialize | 0.051041 |
| joinOneToMany | probe | 12.310041 |
| joinOneToOne | build | 17.063208 |
| joinOneToOne | checksum | 24.984459 |
| joinOneToOne | decode | 1.136583 |
| joinOneToOne | materialize | 0.057041 |
| joinOneToOne | probe | 13.678500 |
| joinSkewed | build | 0.009083 |
| joinSkewed | checksum | 0.029416 |
| joinSkewed | decode | 0.521875 |
| joinSkewed | materialize | 0.010583 |
| joinSkewed | probe | 1.057875 |
| joinSparse | build | 1.297458 |
| joinSparse | checksum | 2.504042 |
| joinSparse | decode | 0.766292 |
| joinSparse | materialize | 0.045125 |
| joinSparse | probe | 10.550334 |
| semiJoinSparse | build | 1.191583 |
| semiJoinSparse | checksum | 0.877792 |
| semiJoinSparse | decode | 0.359583 |
| semiJoinSparse | materialize | 0.034833 |
| semiJoinSparse | probe | 10.765667 |

Individual stage samples are in `raw/stages.tsv`; `stage-summary.tsv`
records median time and median fraction. `metrics.tsv` is the stable input
for a later same-harness candidate receipt. Exact candidate checksums and
cardinalities are in `validation.tsv`; semantic oracle agreement remains
ratified by the small cross-platform court.
