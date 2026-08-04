# frame4s secondary-index baseline

Full pre-implementation lower-bound receipt.

Both workloads scan the same stable unsorted `Int32` values and materialize
the selected values before checksumming. Query-key construction is outside
the timed method. No secondary index exists in this phase.

| Benchmark | Input rows | Mode | Time or throughput | Allocation | Output rows | Checksum |
|---|---:|---:|---:|---:|---:|---:|
| `IndexScanCourt.batch32Lookup` | 100000 | avgt | 0.487895 ms/op | 174.692 B/op | 32 | `13590724320720048604` |
| `IndexScanCourt.batch32Lookup` | 100000 | thrpt | 2.02324 ops/ms | 174.776 B/op | 32 | `13590724320720048604` |
| `IndexScanCourt.batch32Lookup` | 1000000 | avgt | 5.11105 ms/op | 237.520 B/op | 32 | `14554966086508252864` |
| `IndexScanCourt.batch32Lookup` | 1000000 | thrpt | 0.185352 ops/ms | 241.283 B/op | 32 | `14554966086508252864` |
| `IndexScanCourt.singleLookup` | 100000 | avgt | 0.0149500 ms/op | 48.203 B/op | 1 | `100030` |
| `IndexScanCourt.singleLookup` | 100000 | thrpt | 72.9422 ops/ms | 48.188 B/op | 1 | `100030` |
| `IndexScanCourt.singleLookup` | 1000000 | avgt | 0.132589 ms/op | 49.814 B/op | 1 | `1000030` |
| `IndexScanCourt.singleLookup` | 1000000 | thrpt | 7.88306 ops/ms | 49.735 B/op | 1 | `1000030` |

Raw JMH output is under `raw/`; `validation.tsv` and
`environment.properties` carry semantic and runtime provenance.
