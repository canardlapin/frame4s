# Join key-order sensitivity receipt

Full deterministic order-sensitivity receipt.

Both comparators eagerly join the same 1000000-row one-to-one key/value multiset. Only row ordering changes; seed `20260730` and permutation hashes are recorded in `environment.properties`.

| Key order | pandas median | Polars median |
|---|---:|---:|
| sorted | 1.946 ms | 49.573 ms |
| right-shuffled | 32.278 ms | 48.620 ms |
| both-shuffled | 40.093 ms | 50.361 ms |

The experiment establishes sensitivity to key ordering. It does not by itself
identify either comparator's internal physical join algorithm.

Every individual timing is in `raw/samples.csv`; aggregate medians and ranges
are in `raw/timings.csv`; cardinality and value-binding invariants are in
`validation.tsv`.
