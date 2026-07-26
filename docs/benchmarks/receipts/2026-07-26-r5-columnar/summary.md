# frame4s benchmark court receipt

Full court receipt using the committed warmup, measurement, fork, heap, and profiler settings.

The semantic reference interpreter is an executable oracle, not the optimized backend.
Every `ColumnarBenchmarks` row refuses fallback and must carry the same checksum and
output cardinality as its corresponding `ReferenceBenchmarks` row.
Saddle rows are ranked only for the three shapes marked comparable; SQL duplicate-key
joins, dictionary layout, CSV acquisition, and owned-table construction have no claimed
Saddle-equivalent result. Scautable is intentionally excluded from relational rankings.

| Benchmark | Mode | Time or throughput | Allocation | Output rows | Checksum | Comparison status |
|---|---:|---:|---:|---:|---:|---|
| `ColumnarBenchmarks.antiJoinSparse` | avgt | 0.0415971 ms/op | 162558.719 B/op | 900 | `9254474385373743492` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.antiJoinSparse` | thrpt | 20.0985 ops/ms | 162553.263 B/op | 900 | `9254474385373743492` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.distinctLowCardinality` | avgt | 0.0877158 ms/op | 245082.312 B/op | 334 | `9976595437625738613` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.distinctLowCardinality` | thrpt | 9.75409 ops/ms | 245082.856 B/op | 334 | `9976595437625738613` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.filter` | avgt | 0.0280584 ms/op | 85400.577 B/op | 500 | `12781342423538519465` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.filter` | thrpt | 29.1043 ops/ms | 85400.709 B/op | 500 | `12781342423538519465` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.fusedFilterProjectArithmetic` | avgt | 0.00683079 ms/op | 18768.125 B/op | 500 | `17371320548491255848` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.fusedFilterProjectArithmetic` | thrpt | 120.534 ops/ms | 18608.153 B/op | 500 | `17371320548491255848` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.groupedHighCardinality` | avgt | 0.0597540 ms/op | 217569.859 B/op | 1000 | `17355408869672672305` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.groupedHighCardinality` | thrpt | 14.4143 ops/ms | 217570.002 B/op | 1000 | `17355408869672672305` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.groupedLowCardinality` | avgt | 0.0296511 ms/op | 55192.872 B/op | 16 | `3389499453517600585` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.groupedLowCardinality` | thrpt | 55.3601 ops/ms | 54464.548 B/op | 16 | `3389499453517600585` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinOneToMany` | avgt | 0.160574 ms/op | 382299.846 B/op | 1000 | `8449815210990138133` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinOneToMany` | thrpt | 10.0083 ops/ms | 382202.688 B/op | 1000 | `8449815210990138133` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinOneToOne` | avgt | 0.132936 ms/op | 390395.568 B/op | 1000 | `17280505836941918740` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinOneToOne` | thrpt | 9.12344 ops/ms | 390394.949 B/op | 1000 | `17280505836941918740` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinSkewed` | avgt | 0.0911807 ms/op | 362490.437 B/op | 1000 | `6659192972797761300` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinSkewed` | thrpt | 11.0009 ops/ms | 362490.444 B/op | 1000 | `6659192972797761300` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinSparse` | avgt | 0.0235560 ms/op | 49856.600 B/op | 100 | `12312468580073833310` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinSparse` | thrpt | 65.9638 ops/ms | 49920.406 B/op | 100 | `12312468580073833310` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.nullableScan` | avgt | 0.00748092 ms/op | 10688.146 B/op | 1000 | `1522408454677155389` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.nullableScan` | thrpt | 134.501 ops/ms | 10688.145 B/op | 1000 | `1522408454677155389` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.primitiveScan` | avgt | 0.00147607 ms/op | 6208.027 B/op | 1000 | `6204110518321444316` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.primitiveScan` | thrpt | 675.800 ops/ms | 6136.027 B/op | 1000 | `6204110518321444316` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.semiJoinSparse` | avgt | 0.00912510 ms/op | 36976.246 B/op | 100 | `10490634394616260580` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.semiJoinSparse` | thrpt | 108.761 ops/ms | 36976.248 B/op | 100 | `10490634394616260580` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.unionAll` | avgt | 0.0123497 ms/op | 27240.316 B/op | 2000 | `376105377322046160` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.unionAll` | thrpt | 79.9579 ops/ms | 27304.325 B/op | 2000 | `376105377322046160` | candidate kernel; oracle checksum required |
| `ReferenceBenchmarks.antiJoinSparse` | avgt | 3.94035 ms/op | 22922182.790 B/op | 900 | `9254474385373743492` | reference result |
| `ReferenceBenchmarks.antiJoinSparse` | thrpt | 0.253859 ops/ms | 22936585.321 B/op | 900 | `9254474385373743492` | reference result |
| `ReferenceBenchmarks.boundedScalarDecode` | avgt | 0.00274581 ms/op | 14328.070 B/op | 32 | `3392524538599714678` | reference result |
| `ReferenceBenchmarks.boundedScalarDecode` | thrpt | 353.013 ops/ms | 14328.073 B/op | 32 | `3392524538599714678` | reference result |
| `ReferenceBenchmarks.csvDecode` | avgt | 0.298622 ms/op | 668044.762 B/op | 1000 | `29791143` | reference result |
| `ReferenceBenchmarks.csvDecode` | thrpt | 3.00566 ops/ms | 740424.187 B/op | 1000 | `29791143` | reference result |
| `ReferenceBenchmarks.dictionaryScan` | avgt | 0.0391096 ms/op | 152000.999 B/op | 1000 | `17333564375837731125` | reference result |
| `ReferenceBenchmarks.dictionaryScan` | thrpt | 25.2387 ops/ms | 152001.016 B/op | 1000 | `17333564375837731125` | reference result |
| `ReferenceBenchmarks.distinctLowCardinality` | avgt | 0.113145 ms/op | 645009.775 B/op | 334 | `9976595437625738613` | reference result |
| `ReferenceBenchmarks.distinctLowCardinality` | thrpt | 8.65984 ops/ms | 645009.854 B/op | 334 | `9976595437625738613` | reference result |
| `ReferenceBenchmarks.filter` | avgt | 0.140074 ms/op | 849425.930 B/op | 500 | `12781342423538519465` | reference result |
| `ReferenceBenchmarks.filter` | thrpt | 7.10812 ops/ms | 849723.915 B/op | 500 | `12781342423538519465` | reference result |
| `ReferenceBenchmarks.fusedFilterProjectArithmetic` | avgt | 0.141454 ms/op | 894497.950 B/op | 500 | `17371320548491255848` | reference result |
| `ReferenceBenchmarks.fusedFilterProjectArithmetic` | thrpt | 7.14112 ops/ms | 862525.915 B/op | 500 | `17371320548491255848` | reference result |
| `ReferenceBenchmarks.groupedHighCardinality` | avgt | 0.261378 ms/op | 1293232.934 B/op | 1000 | `17355408869672672305` | reference result |
| `ReferenceBenchmarks.groupedHighCardinality` | thrpt | 3.83597 ops/ms | 1277163.377 B/op | 1000 | `17355408869672672305` | reference result |
| `ReferenceBenchmarks.groupedLowCardinality` | avgt | 0.228527 ms/op | 994901.575 B/op | 16 | `3389499453517600585` | reference result |
| `ReferenceBenchmarks.groupedLowCardinality` | thrpt | 4.34832 ops/ms | 994525.625 B/op | 16 | `3389499453517600585` | reference result |
| `ReferenceBenchmarks.joinOneToMany` | avgt | 40.2811 ms/op | 232793860.677 B/op | 1000 | `8449815210990138133` | reference result |
| `ReferenceBenchmarks.joinOneToMany` | thrpt | 0.0250645 ops/ms | 232793859.077 B/op | 1000 | `8449815210990138133` | reference result |
| `ReferenceBenchmarks.joinOneToOne` | avgt | 39.6822 ms/op | 236921622.523 B/op | 1000 | `17280505836941918740` | reference result |
| `ReferenceBenchmarks.joinOneToOne` | thrpt | 0.0249520 ops/ms | 236921622.523 B/op | 1000 | `17280505836941918740` | reference result |
| `ReferenceBenchmarks.joinSkewed` | avgt | 38.2643 ms/op | 222845042.215 B/op | 1000 | `6659192972797761300` | reference result |
| `ReferenceBenchmarks.joinSkewed` | thrpt | 0.0259698 ops/ms | 222845044.290 B/op | 1000 | `6659192972797761300` | reference result |
| `ReferenceBenchmarks.joinSparse` | avgt | 4.01955 ms/op | 23688979.803 B/op | 100 | `12312468580073833310` | reference result |
| `ReferenceBenchmarks.joinSparse` | thrpt | 0.243147 ops/ms | 23688989.255 B/op | 100 | `12312468580073833310` | reference result |
| `ReferenceBenchmarks.nullableScan` | avgt | 0.0798492 ms/op | 467513.100 B/op | 1000 | `1522408454677155389` | reference result |
| `ReferenceBenchmarks.nullableScan` | thrpt | 12.3606 ops/ms | 467369.114 B/op | 1000 | `1522408454677155389` | reference result |
| `ReferenceBenchmarks.primitiveScan` | avgt | 0.0460727 ms/op | 343408.635 B/op | 1000 | `6204110518321444316` | reference result |
| `ReferenceBenchmarks.primitiveScan` | thrpt | 21.5855 ops/ms | 343224.640 B/op | 1000 | `6204110518321444316` | reference result |
| `ReferenceBenchmarks.semiJoinSparse` | avgt | 3.94669 ms/op | 22483059.336 B/op | 100 | `10490634394616260580` | reference result |
| `ReferenceBenchmarks.semiJoinSparse` | thrpt | 0.256283 ops/ms | 22483055.440 B/op | 100 | `10490634394616260580` | reference result |
| `ReferenceBenchmarks.tableConstruction` | avgt | 0.0586320 ms/op | 379645.759 B/op | 1000 | `10243311899862777556` | reference result |
| `ReferenceBenchmarks.tableConstruction` | thrpt | 18.7632 ops/ms | 318510.198 B/op | 1000 | `10243311899862777556` | reference result |
| `ReferenceBenchmarks.unionAll` | avgt | 0.0749878 ms/op | 658121.032 B/op | 2000 | `376105377322046160` | reference result |
| `ReferenceBenchmarks.unionAll` | thrpt | 12.8053 ops/ms | 658145.076 B/op | 2000 | `376105377322046160` | reference result |
| `ReferenceBenchmarks.utf8Scan` | avgt | 0.0362370 ms/op | 286256.926 B/op | 1000 | `17333564375837731125` | reference result |
| `ReferenceBenchmarks.utf8Scan` | thrpt | 27.5417 ops/ms | 286256.927 B/op | 1000 | `17333564375837731125` | reference result |
| `SaddleBenchmarks.filterProjectArithmetic` | avgt | 0.0155952 ms/op | 85584.216 B/op | 500 | `17371320548491255848` | semantically equivalent materialized pipeline |
| `SaddleBenchmarks.filterProjectArithmetic` | thrpt | 63.2781 ops/ms | 85584.218 B/op | 500 | `17371320548491255848` | semantically equivalent materialized pipeline |
| `SaddleBenchmarks.groupedLowCardinality` | avgt | 0.0111035 ms/op | 118968.194 B/op | 16 | `0x1.e7cbp15` | comparable grouped reduction shape |
| `SaddleBenchmarks.groupedLowCardinality` | thrpt | 101.472 ops/ms | 115896.176 B/op | 16 | `0x1.e7cbp15` | comparable grouped reduction shape |
| `SaddleBenchmarks.primitiveScan` | avgt | 0.00106171 ms/op | 0.014 B/op | 1000 | `6204110518321444316` | comparable scan shape |
| `SaddleBenchmarks.primitiveScan` | thrpt | 932.878 ops/ms | 0.015 B/op | 1000 | `6204110518321444316` | comparable scan shape |
| `SpecializedArrayBenchmarks.fusedFilterProjectArithmetic` | avgt | 0.000838705 ms/op | 0.011 B/op | 500 | `17371320548491255848` | specialized lower-bound baseline |
| `SpecializedArrayBenchmarks.fusedFilterProjectArithmetic` | thrpt | 1188.78 ops/ms | 0.011 B/op | 500 | `17371320548491255848` | specialized lower-bound baseline |
| `SpecializedArrayBenchmarks.nullableScan` | avgt | 0.000800910 ms/op | 0.011 B/op | 1000 | `1522408454677155389` | specialized lower-bound baseline |
| `SpecializedArrayBenchmarks.nullableScan` | thrpt | 1253.45 ops/ms | 0.011 B/op | 1000 | `1522408454677155389` | specialized lower-bound baseline |

Raw JMH JSON and console output are in `raw/`. `validation.tsv` records a
single untimed execution of every workload so row counts and checksums travel
with the timing and allocation receipt. `environment.properties` records the
toolchain, hardware description, fixture size, backend, and fallback status.
