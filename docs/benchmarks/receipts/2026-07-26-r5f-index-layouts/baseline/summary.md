# frame4s secondary-index baseline

Full pre-implementation lower-bound receipt.

Both workloads scan the same stable unsorted `Int32` values and materialize
the selected values before checksumming. Query-key construction is outside
the timed method. No secondary index exists in this phase.

| Benchmark | Input rows | Mode | Time or throughput | Allocation | Output rows | Checksum or owned bytes |
|---|---:|---:|---:|---:|---:|---:|
| `IndexScanCourt.batch32Lookup` | 100000 | avgt | 0.598596 ms/op | 176.262 B/op | 32 | `13590724320720048604` |
| `IndexScanCourt.batch32Lookup` | 100000 | thrpt | 1.70972 ops/ms | 176.062 B/op | 32 | `13590724320720048604` |
| `IndexScanCourt.batch32Lookup` | 1000000 | avgt | 6.01283 ms/op | 250.224 B/op | 32 | `14554966086508252864` |
| `IndexScanCourt.batch32Lookup` | 1000000 | thrpt | 0.167895 ops/ms | 249.429 B/op | 32 | `14554966086508252864` |
| `IndexScanCourt.singleLookup` | 100000 | avgt | 0.0154703 ms/op | 48.214 B/op | 1 | `100030` |
| `IndexScanCourt.singleLookup` | 100000 | thrpt | 63.4661 ops/ms | 48.217 B/op | 1 | `100030` |
| `IndexScanCourt.singleLookup` | 1000000 | avgt | 0.159515 ms/op | 50.181 B/op | 1 | `1000030` |
| `IndexScanCourt.singleLookup` | 1000000 | thrpt | 6.25932 ops/ms | 50.201 B/op | 1 | `1000030` |

Raw JMH output is under `raw/`; `validation.tsv` and
`environment.properties` carry semantic and runtime provenance.
