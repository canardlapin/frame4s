# frame4s benchmark court receipt

Full court receipt using the committed warmup, measurement, fork, heap, and profiler settings.

The semantic reference interpreter is an executable oracle, not the optimized backend.
Saddle rows are ranked only for the three shapes marked comparable; SQL duplicate-key
joins, dictionary layout, CSV acquisition, and owned-table construction have no claimed
Saddle-equivalent result. Scautable is intentionally excluded from relational rankings.

| Benchmark | Mode | Time or throughput | Allocation | Output rows | Checksum | Comparison status |
|---|---:|---:|---:|---:|---:|---|
| `ReferenceBenchmarks.boundedScalarDecode` | avgt | 0.00280061 ms/op | 14328.071 B/op | 32 | `3392524538599714678` | reference result |
| `ReferenceBenchmarks.boundedScalarDecode` | thrpt | 349.518 ops/ms | 14328.073 B/op | 32 | `3392524538599714678` | reference result |
| `ReferenceBenchmarks.csvDecode` | avgt | 0.259888 ms/op | 699783.768 B/op | 1000 | `29791143` | reference result |
| `ReferenceBenchmarks.csvDecode` | thrpt | 4.44192 ops/ms | 627038.797 B/op | 1000 | `29791143` | reference result |
| `ReferenceBenchmarks.dictionaryScan` | avgt | 0.0405078 ms/op | 152001.018 B/op | 1000 | `17333564375837731125` | reference result |
| `ReferenceBenchmarks.dictionaryScan` | thrpt | 25.3012 ops/ms | 152001.021 B/op | 1000 | `17333564375837731125` | reference result |
| `ReferenceBenchmarks.filter` | avgt | 0.141243 ms/op | 878833.944 B/op | 500 | `12781342423538519465` | reference result |
| `ReferenceBenchmarks.filter` | thrpt | 7.11338 ops/ms | 849033.937 B/op | 500 | `12781342423538519465` | reference result |
| `ReferenceBenchmarks.fusedFilterProjectArithmetic` | avgt | 0.153134 ms/op | 861296.757 B/op | 500 | `17371320548491255848` | reference result |
| `ReferenceBenchmarks.fusedFilterProjectArithmetic` | thrpt | 7.24238 ops/ms | 861264.286 B/op | 500 | `17371320548491255848` | reference result |
| `ReferenceBenchmarks.groupedHighCardinality` | avgt | 0.264655 ms/op | 1276419.114 B/op | 1000 | `17355408869672672305` | reference result |
| `ReferenceBenchmarks.groupedHighCardinality` | thrpt | 3.73657 ops/ms | 1276474.449 B/op | 1000 | `17355408869672672305` | reference result |
| `ReferenceBenchmarks.groupedLowCardinality` | avgt | 0.238527 ms/op | 993693.362 B/op | 16 | `3389499453517600585` | reference result |
| `ReferenceBenchmarks.groupedLowCardinality` | thrpt | 4.35653 ops/ms | 993717.863 B/op | 16 | `3389499453517600585` | reference result |
| `ReferenceBenchmarks.joinOneToMany` | avgt | 39.4405 ms/op | 232792722.831 B/op | 1000 | `8449815210990138133` | reference result |
| `ReferenceBenchmarks.joinOneToMany` | thrpt | 0.0253341 ops/ms | 232792720.615 B/op | 1000 | `8449815210990138133` | reference result |
| `ReferenceBenchmarks.joinOneToOne` | avgt | 39.5431 ms/op | 236920485.169 B/op | 1000 | `17280505836941918740` | reference result |
| `ReferenceBenchmarks.joinOneToOne` | thrpt | 0.0251383 ops/ms | 236920486.400 B/op | 1000 | `17280505836941918740` | reference result |
| `ReferenceBenchmarks.joinSkewed` | avgt | 38.7996 ms/op | 222843928.114 B/op | 1000 | `6659192972797761300` | reference result |
| `ReferenceBenchmarks.joinSkewed` | thrpt | 0.0259877 ops/ms | 222843899.200 B/op | 1000 | `6659192972797761300` | reference result |
| `ReferenceBenchmarks.joinSparse` | avgt | 4.04495 ms/op | 23687841.130 B/op | 100 | `12312468580073833310` | reference result |
| `ReferenceBenchmarks.joinSparse` | thrpt | 0.247473 ops/ms | 23687844.976 B/op | 100 | `12312468580073833310` | reference result |
| `ReferenceBenchmarks.nullableScan` | avgt | 0.0795016 ms/op | 466785.093 B/op | 1000 | `1522408454677155389` | reference result |
| `ReferenceBenchmarks.nullableScan` | thrpt | 12.2987 ops/ms | 466729.118 B/op | 1000 | `1522408454677155389` | reference result |
| `ReferenceBenchmarks.primitiveScan` | avgt | 0.0462944 ms/op | 342656.638 B/op | 1000 | `6204110518321444316` | reference result |
| `ReferenceBenchmarks.primitiveScan` | thrpt | 21.7131 ops/ms | 342631.045 B/op | 1000 | `6204110518321444316` | reference result |
| `ReferenceBenchmarks.tableConstruction` | avgt | 0.0529173 ms/op | 320720.466 B/op | 1000 | `10243311899862777556` | reference result |
| `ReferenceBenchmarks.tableConstruction` | thrpt | 15.9042 ops/ms | 363129.159 B/op | 1000 | `10243311899862777556` | reference result |
| `ReferenceBenchmarks.utf8Scan` | avgt | 0.0359829 ms/op | 286256.924 B/op | 1000 | `17333564375837731125` | reference result |
| `ReferenceBenchmarks.utf8Scan` | thrpt | 25.4714 ops/ms | 278257.014 B/op | 1000 | `17333564375837731125` | reference result |
| `SaddleBenchmarks.filterProjectArithmetic` | avgt | 0.0155019 ms/op | 85584.214 B/op | 500 | `17371320548491255848` | semantically equivalent materialized pipeline |
| `SaddleBenchmarks.filterProjectArithmetic` | thrpt | 64.2648 ops/ms | 85584.215 B/op | 500 | `17371320548491255848` | semantically equivalent materialized pipeline |
| `SaddleBenchmarks.groupedLowCardinality` | avgt | 0.0111544 ms/op | 118968.194 B/op | 16 | `0x1.e7cbp15` | comparable grouped reduction shape |
| `SaddleBenchmarks.groupedLowCardinality` | thrpt | 90.1073 ops/ms | 118968.195 B/op | 16 | `0x1.e7cbp15` | comparable grouped reduction shape |
| `SaddleBenchmarks.primitiveScan` | avgt | 0.00105557 ms/op | 0.014 B/op | 1000 | `6204110518321444316` | comparable scan shape |
| `SaddleBenchmarks.primitiveScan` | thrpt | 927.700 ops/ms | 0.015 B/op | 1000 | `6204110518321444316` | comparable scan shape |
| `SpecializedArrayBenchmarks.fusedFilterProjectArithmetic` | avgt | 0.000831457 ms/op | 0.011 B/op | 500 | `17371320548491255848` | specialized lower-bound baseline |
| `SpecializedArrayBenchmarks.fusedFilterProjectArithmetic` | thrpt | 1200.00 ops/ms | 0.011 B/op | 500 | `17371320548491255848` | specialized lower-bound baseline |
| `SpecializedArrayBenchmarks.nullableScan` | avgt | 0.000801498 ms/op | 0.011 B/op | 1000 | `1522408454677155389` | specialized lower-bound baseline |
| `SpecializedArrayBenchmarks.nullableScan` | thrpt | 1250.20 ops/ms | 0.011 B/op | 1000 | `1522408454677155389` | specialized lower-bound baseline |

Raw JMH JSON and console output are in `raw/`. `validation.tsv` records a
single untimed execution of every workload so row counts and checksums travel
with the timing and allocation receipt. `environment.properties` records the
toolchain, hardware description, fixture size, backend, and fallback status.
