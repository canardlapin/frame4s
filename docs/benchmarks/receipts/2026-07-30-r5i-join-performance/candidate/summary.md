# frame4s one-shot join performance court

Full receipt. Decision: the candidate passes its >=1.15x one-to-one speed gate; allocation passes its absolute and no-regression gates. Whether a parallel probe is admitted is stated by the candidate receipt that enables it.

`execution-only` constructs and blackholes the detached result, then closes it.
`consumed-checksum` additionally walks every output cell in a serial checksum.
Comparator ratios must use the execution-only row; the consumed row remains a
validation and consumption-cost court.

| Workload | Execution-only ms/op | Consumed ms/op | Approx. checksum share | Execution allocation B/op | Same-harness speedup | Allocation ratio |
|---|---:|---:|---:|---:|---:|---:|
| joinOneToOne | 29.902491 | 68.121798 | 56.1% | 65173704 | 4.34x | 0.589 |
| joinOneToMany | 30.603942 | 67.939846 | 55.0% | 69173682 | 2.93x | 0.625 |
| joinSparse | 15.635404 | 18.529141 | 15.6% | 17952992 | 1.50x | 0.769 |
| joinSkewed | 2.077880 | 2.099950 | 1.1% | 12062496 | 4.08x | 0.997 |
| semiJoinSparse | 16.496914 | 17.034262 | 3.2% | 17152937 | 1.31x | 0.839 |
| antiJoinSparse | 17.530848 | 27.370162 | 35.9% | 24352922 | 3.24x | 0.586 |

## One-to-one stage attribution

| Stage | Median ms |
|---|---:|
| build | 20.272292 |
| checksum | 25.702042 |
| decode | 0.643541 |
| materialize | 0.059750 |
| probe | 17.151834 |

Individual stage samples are in `raw/stages.tsv`; `stage-summary.tsv`
records median time and median fraction. `metrics.tsv` is the stable input
for a later same-harness candidate receipt. Exact candidate checksums and
cardinalities are in `validation.tsv`; semantic oracle agreement remains
ratified by the small cross-platform court.
