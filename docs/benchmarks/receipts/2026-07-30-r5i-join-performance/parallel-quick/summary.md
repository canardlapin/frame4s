# frame4s one-shot join performance court

Quick provisional receipt. Decision: the candidate passes its >=1.15x one-to-one speed gate; allocation fails at least one absolute or representative no-regression gate. Whether a parallel probe is admitted is stated by the candidate receipt that enables it.

`execution-only` constructs and blackholes the detached result, then closes it.
`consumed-checksum` additionally walks every output cell in a serial checksum.
Comparator ratios must use the execution-only row; the consumed row remains a
validation and consumption-cost court.

| Workload | Execution-only ms/op | Consumed ms/op | Approx. checksum share | Execution allocation B/op | Same-harness speedup | Allocation ratio |
|---|---:|---:|---:|---:|---:|---:|
| joinOneToOne | 28.315563 | 55.294875 | 48.8% | 97073819 | 1.49x | 1.489 |
| joinOneToMany | 26.656701 | 59.574750 | 55.3% | 101388191 | 1.50x | 1.466 |
| joinSparse | 5.682024 | 8.793278 | 35.4% | 21233600 | 2.97x | 1.183 |
| joinSkewed | 3.116299 | 3.694869 | 15.7% | 12231463 | 0.83x | 1.014 |
| semiJoinSparse | 8.707491 | 8.616894 | 0.0% | 19329426 | 2.15x | 1.127 |
| antiJoinSparse | 4.821158 | 13.227868 | 63.6% | 24468361 | 4.64x | 1.005 |

## One-to-one stage attribution

| Stage | Median ms |
|---|---:|
| build | 21.111917 |
| checksum | 25.797875 |
| decode | 0.905666 |
| materialize | 0.063416 |
| probe | 4.792625 |

Individual stage samples are in `raw/stages.tsv`; `stage-summary.tsv`
records median time and median fraction. `metrics.tsv` is the stable input
for a later same-harness candidate receipt. Exact candidate checksums and
cardinalities are in `validation.tsv`; semantic oracle agreement remains
ratified by the small cross-platform court.
