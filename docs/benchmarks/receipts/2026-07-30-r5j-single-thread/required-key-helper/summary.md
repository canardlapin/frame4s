# frame4s one-shot join performance court

Full receipt. Decision: the candidate passes its >=1.15x one-to-one speed gate; allocation passes its absolute and no-regression gates. Whether a parallel probe is admitted is stated by the candidate receipt that enables it.

`execution-only` constructs and blackholes the detached result, then closes it.
`consumed-checksum` additionally walks every output cell in a serial checksum.
Comparator ratios must use the execution-only row; the consumed row remains a
validation and consumption-cost court.

| Workload | Execution-only ms/op | Consumed ms/op | Approx. checksum share | Execution allocation B/op | Same-harness speedup | Allocation ratio |
|---|---:|---:|---:|---:|---:|---:|
| joinOneToOne | 32.540649 | 70.664702 | 54.0% | 65173799 | 1.32x | 1.000 |
| joinOneToMany | 39.762233 | 75.920469 | 47.6% | 69174040 | 0.92x | 1.000 |
| joinSparse | 17.575985 | 20.196315 | 13.0% | 17953098 | 1.45x | 1.000 |
| joinSkewed | 3.080479 | 3.984407 | 22.7% | 12062912 | 0.99x | 1.000 |
| semiJoinSparse | 17.812826 | 19.414722 | 8.3% | 17152955 | 0.97x | 1.000 |
| antiJoinSparse | 25.498442 | 27.069428 | 5.8% | 24353383 | 0.76x | 1.000 |

## Stage attribution

| Workload | Stage | Median ms |
|---|---|---:|
| antiJoinSparse | build | 1.005541 |
| antiJoinSparse | checksum | 8.550375 |
| antiJoinSparse | decode | 0.345208 |
| antiJoinSparse | materialize | 0.047084 |
| antiJoinSparse | probe | 16.982500 |
| joinOneToMany | build | 17.275208 |
| joinOneToMany | checksum | 26.204833 |
| joinOneToMany | decode | 0.605959 |
| joinOneToMany | materialize | 0.058458 |
| joinOneToMany | probe | 17.906750 |
| joinOneToOne | build | 22.217583 |
| joinOneToOne | checksum | 26.298416 |
| joinOneToOne | decode | 1.062500 |
| joinOneToOne | materialize | 0.063417 |
| joinOneToOne | probe | 26.141459 |
| joinSkewed | build | 0.011958 |
| joinSkewed | checksum | 0.033709 |
| joinSkewed | decode | 0.296250 |
| joinSkewed | materialize | 0.022208 |
| joinSkewed | probe | 3.012375 |
| joinSparse | build | 1.079792 |
| joinSparse | checksum | 2.607875 |
| joinSparse | decode | 0.381416 |
| joinSparse | materialize | 0.039125 |
| joinSparse | probe | 16.828875 |
| semiJoinSparse | build | 0.991916 |
| semiJoinSparse | checksum | 0.960625 |
| semiJoinSparse | decode | 0.325291 |
| semiJoinSparse | materialize | 0.031833 |
| semiJoinSparse | probe | 16.058417 |

Individual stage samples are in `raw/stages.tsv`; `stage-summary.tsv`
records median time and median fraction. `metrics.tsv` is the stable input
for a later same-harness candidate receipt. Exact candidate checksums and
cardinalities are in `validation.tsv`; semantic oracle agreement remains
ratified by the small cross-platform court.
