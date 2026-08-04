# dplyr-style practical pipeline court

Full JMH practical-pipeline receipt. Fixture construction, typed query construction, validation, and teardown occur
outside timed methods. The columnar rows forbid fallback and match the semantic reference
row count, raw-bit checksum, structural checksum, and floating signature before
measurement.

| Benchmark | Mode | Time or throughput | Allocation | Output rows | Checksum |
|---|---:|---:|---:|---:|---:|
| `PracticalPipelineColumnarCourt.filterWithColumnsSelect` | avgt | 0.487033 ms/op | 120273.246 B/op | 667 | `7454054053978914851` |
| `PracticalPipelineColumnarCourt.filterWithColumnsSelect` | thrpt | 1.59919 ops/ms | 120042.831 B/op | 667 | `7454054053978914851` |
| `PracticalPipelineColumnarCourt.selectGroupSummarise` | avgt | 0.722191 ms/op | 33531.993 B/op | 35 | `4312025928662968800` |
| `PracticalPipelineColumnarCourt.selectGroupSummarise` | thrpt | 1.39773 ops/ms | 33127.822 B/op | 35 | `4312025928662968800` |
| `PracticalPipelineReferenceCourt.filterWithColumnsSelect` | avgt | 3.16415 ms/op | 9197332.526 B/op | 667 | `7454054053978914851` |
| `PracticalPipelineReferenceCourt.filterWithColumnsSelect` | thrpt | 0.313021 ops/ms | 9739129.479 B/op | 667 | `7454054053978914851` |
| `PracticalPipelineReferenceCourt.selectGroupSummarise` | avgt | 6.82210 ms/op | 17594900.012 B/op | 35 | `4312025928662968800` |
| `PracticalPipelineReferenceCourt.selectGroupSummarise` | thrpt | 0.144703 ops/ms | 17595947.572 B/op | 35 | `4312025928662968800` |

`filterWithColumnsSelect` combines two UTF-8 predicates, two immutable `withColumn`
derivations, and a final projection. `selectGroupSummarise` projects four columns, groups
by two nullable keys, and computes means over two measures.
