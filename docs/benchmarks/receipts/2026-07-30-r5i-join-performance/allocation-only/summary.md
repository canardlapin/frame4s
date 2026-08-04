# frame4s one-shot join performance court

Full receipt. Decision: the candidate passes its >=1.15x one-to-one speed gate; allocation passes its absolute and no-regression gates. Whether a parallel probe is admitted is stated by the candidate receipt that enables it.

`execution-only` constructs and blackholes the detached result, then closes it.
`consumed-checksum` additionally walks every output cell in a serial checksum.
Comparator ratios must use the execution-only row; the consumed row remains a
validation and consumption-cost court.

| Workload | Execution-only ms/op | Consumed ms/op | Approx. checksum share | Execution allocation B/op | Same-harness speedup | Allocation ratio |
|---|---:|---:|---:|---:|---:|---:|
| joinOneToOne | 42.279008 | 80.931832 | 47.8% | 65174059 | 3.07x | 0.589 |
| joinOneToMany | 39.886622 | 83.816457 | 52.4% | 69174135 | 2.25x | 0.625 |
| joinSparse | 16.857777 | 19.767577 | 14.7% | 17953024 | 1.39x | 0.769 |
| joinSkewed | 2.589915 | 2.957270 | 12.4% | 12062973 | 3.28x | 0.997 |
| semiJoinSparse | 18.696079 | 17.723739 | 0.0% | 17152934 | 1.15x | 0.839 |
| antiJoinSparse | 22.391204 | 29.095879 | 23.0% | 24353301 | 2.54x | 0.586 |

## One-to-one stage attribution

| Stage | Median ms |
|---|---:|
| build | 30.888958 |
| checksum | 26.724125 |
| decode | 1.049083 |
| materialize | 0.085875 |
| probe | 26.770375 |

Individual stage samples are in `raw/stages.tsv`; `stage-summary.tsv`
records median time and median fraction. `metrics.tsv` is the stable input
for a later same-harness candidate receipt. Exact candidate checksums and
cardinalities are in `validation.tsv`; semantic oracle agreement remains
ratified by the small cross-platform court.
