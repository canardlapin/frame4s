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
| `ColumnarBenchmarks.antiJoinSparse` | avgt | 0.0421804 ms/op | 162555.524 B/op | 900 | `9254474385373743492` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.antiJoinSparse` | thrpt | 23.0488 ops/ms | 162555.697 B/op | 900 | `9254474385373743492` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.dictionaryScan` | avgt | 0.00675795 ms/op | 5320.176 B/op | 1000 | `17333564375837731125` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.dictionaryScan` | thrpt | 148.944 ops/ms | 5320.173 B/op | 1000 | `17333564375837731125` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.distinctLowCardinality` | avgt | 0.0909210 ms/op | 245082.403 B/op | 334 | `9976595437625738613` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.distinctLowCardinality` | thrpt | 11.2770 ops/ms | 245082.332 B/op | 334 | `9976595437625738613` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.filter` | avgt | 0.0189596 ms/op | 61280.393 B/op | 500 | `12781342423538519465` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.filter` | thrpt | 51.5970 ops/ms | 61280.400 B/op | 500 | `12781342423538519465` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.fusedFilterProjectArithmetic` | avgt | 0.00682947 ms/op | 18552.125 B/op | 500 | `17371320548491255848` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.fusedFilterProjectArithmetic` | thrpt | 150.850 ops/ms | 18584.121 B/op | 500 | `17371320548491255848` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.groupedHighCardinality` | avgt | 0.0632093 ms/op | 217537.859 B/op | 1000 | `17355408869672672305` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.groupedHighCardinality` | thrpt | 16.9784 ops/ms | 217401.767 B/op | 1000 | `17355408869672672305` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.groupedLowCardinality` | avgt | 0.0192890 ms/op | 55072.587 B/op | 16 | `3389499453517600585` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.groupedLowCardinality` | thrpt | 54.8422 ops/ms | 55216.561 B/op | 16 | `3389499453517600585` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinOneToMany` | avgt | 0.0965823 ms/op | 382202.592 B/op | 1000 | `8449815210990138133` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinOneToMany` | thrpt | 10.3613 ops/ms | 382202.597 B/op | 1000 | `8449815210990138133` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinOneToOne` | avgt | 0.105184 ms/op | 390458.820 B/op | 1000 | `17280505836941918740` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinOneToOne` | thrpt | 9.74087 ops/ms | 390394.759 B/op | 1000 | `17280505836941918740` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinSkewed` | avgt | 0.0911506 ms/op | 362490.463 B/op | 1000 | `6659192972797761300` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinSkewed` | thrpt | 10.4834 ops/ms | 362490.560 B/op | 1000 | `6659192972797761300` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinSparse` | avgt | 0.0156226 ms/op | 49856.414 B/op | 100 | `12312468580073833310` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinSparse` | thrpt | 66.5981 ops/ms | 49856.403 B/op | 100 | `12312468580073833310` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.nullableScan` | avgt | 0.00734055 ms/op | 10688.142 B/op | 1000 | `1522408454677155389` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.nullableScan` | thrpt | 128.325 ops/ms | 10688.152 B/op | 1000 | `1522408454677155389` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.primitiveScan` | avgt | 0.00146369 ms/op | 6136.027 B/op | 1000 | `6204110518321444316` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.primitiveScan` | thrpt | 675.365 ops/ms | 6208.027 B/op | 1000 | `6204110518321444316` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.semiJoinSparse` | avgt | 0.00922411 ms/op | 36976.248 B/op | 100 | `10490634394616260580` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.semiJoinSparse` | thrpt | 108.397 ops/ms | 36976.248 B/op | 100 | `10490634394616260580` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.unionAll` | avgt | 0.0128955 ms/op | 27240.330 B/op | 2000 | `376105377322046160` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.unionAll` | thrpt | 80.3790 ops/ms | 27240.319 B/op | 2000 | `376105377322046160` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.utf8Scan` | avgt | 0.00504589 ms/op | 22144.145 B/op | 1000 | `17333564375837731125` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.utf8Scan` | thrpt | 196.926 ops/ms | 22144.143 B/op | 1000 | `17333564375837731125` | candidate kernel; oracle checksum required |
| `ReferenceBenchmarks.antiJoinSparse` | avgt | 3.97899 ms/op | 22922188.450 B/op | 900 | `9254474385373743492` | reference result |
| `ReferenceBenchmarks.antiJoinSparse` | thrpt | 0.249256 ops/ms | 22936581.698 B/op | 900 | `9254474385373743492` | reference result |
| `ReferenceBenchmarks.boundedScalarDecode` | avgt | 0.00272670 ms/op | 14328.070 B/op | 32 | `3392524538599714678` | reference result |
| `ReferenceBenchmarks.boundedScalarDecode` | thrpt | 355.679 ops/ms | 14328.072 B/op | 32 | `3392524538599714678` | reference result |
| `ReferenceBenchmarks.csvDecode` | avgt | 0.324532 ms/op | 740405.429 B/op | 1000 | `29791143` | reference result |
| `ReferenceBenchmarks.csvDecode` | thrpt | 3.13521 ops/ms | 740285.940 B/op | 1000 | `29791143` | reference result |
| `ReferenceBenchmarks.dictionaryScan` | avgt | 0.0387591 ms/op | 152000.989 B/op | 1000 | `17333564375837731125` | reference result |
| `ReferenceBenchmarks.dictionaryScan` | thrpt | 26.2161 ops/ms | 152000.976 B/op | 1000 | `17333564375837731125` | reference result |
| `ReferenceBenchmarks.distinctLowCardinality` | avgt | 0.115514 ms/op | 645009.829 B/op | 334 | `9976595437625738613` | reference result |
| `ReferenceBenchmarks.distinctLowCardinality` | thrpt | 8.58543 ops/ms | 629026.701 B/op | 334 | `9976595437625738613` | reference result |
| `ReferenceBenchmarks.filter` | avgt | 0.140399 ms/op | 879744.308 B/op | 500 | `12781342423538519465` | reference result |
| `ReferenceBenchmarks.filter` | thrpt | 7.06463 ops/ms | 849890.529 B/op | 500 | `12781342423538519465` | reference result |
| `ReferenceBenchmarks.fusedFilterProjectArithmetic` | avgt | 0.142935 ms/op | 918440.704 B/op | 500 | `17371320548491255848` | reference result |
| `ReferenceBenchmarks.fusedFilterProjectArithmetic` | thrpt | 7.09999 ops/ms | 869438.388 B/op | 500 | `17371320548491255848` | reference result |
| `ReferenceBenchmarks.groupedHighCardinality` | avgt | 0.267089 ms/op | 1277190.627 B/op | 1000 | `17355408869672672305` | reference result |
| `ReferenceBenchmarks.groupedHighCardinality` | thrpt | 3.75172 ops/ms | 1277289.921 B/op | 1000 | `17355408869672672305` | reference result |
| `ReferenceBenchmarks.groupedLowCardinality` | avgt | 0.234269 ms/op | 994510.090 B/op | 16 | `3389499453517600585` | reference result |
| `ReferenceBenchmarks.groupedLowCardinality` | thrpt | 4.23870 ops/ms | 994512.678 B/op | 16 | `3389499453517600585` | reference result |
| `ReferenceBenchmarks.joinOneToMany` | avgt | 39.8336 ms/op | 232793860.308 B/op | 1000 | `8449815210990138133` | reference result |
| `ReferenceBenchmarks.joinOneToMany` | thrpt | 0.0247644 ops/ms | 232793870.626 B/op | 1000 | `8449815210990138133` | reference result |
| `ReferenceBenchmarks.joinOneToOne` | avgt | 40.5340 ms/op | 236921621.908 B/op | 1000 | `17280505836941918740` | reference result |
| `ReferenceBenchmarks.joinOneToOne` | thrpt | 0.0250830 ops/ms | 236921622.523 B/op | 1000 | `17280505836941918740` | reference result |
| `ReferenceBenchmarks.joinSkewed` | avgt | 38.1545 ms/op | 222845033.486 B/op | 1000 | `6659192972797761300` | reference result |
| `ReferenceBenchmarks.joinSkewed` | thrpt | 0.0261052 ops/ms | 222845036.000 B/op | 1000 | `6659192972797761300` | reference result |
| `ReferenceBenchmarks.joinSparse` | avgt | 4.01638 ms/op | 23688977.149 B/op | 100 | `12312468580073833310` | reference result |
| `ReferenceBenchmarks.joinSparse` | thrpt | 0.240366 ops/ms | 23689000.828 B/op | 100 | `12312468580073833310` | reference result |
| `ReferenceBenchmarks.nullableScan` | avgt | 0.0791639 ms/op | 467361.090 B/op | 1000 | `1522408454677155389` | reference result |
| `ReferenceBenchmarks.nullableScan` | thrpt | 12.3037 ops/ms | 467361.119 B/op | 1000 | `1522408454677155389` | reference result |
| `ReferenceBenchmarks.primitiveScan` | avgt | 0.0462764 ms/op | 343320.637 B/op | 1000 | `6204110518321444316` | reference result |
| `ReferenceBenchmarks.primitiveScan` | thrpt | 21.5139 ops/ms | 343352.641 B/op | 1000 | `6204110518321444316` | reference result |
| `ReferenceBenchmarks.semiJoinSparse` | avgt | 3.88480 ms/op | 22483052.935 B/op | 100 | `10490634394616260580` | reference result |
| `ReferenceBenchmarks.semiJoinSparse` | thrpt | 0.255593 ops/ms | 22483061.430 B/op | 100 | `10490634394616260580` | reference result |
| `ReferenceBenchmarks.tableConstruction` | avgt | 0.0532472 ms/op | 317682.559 B/op | 1000 | `10243311899862777556` | reference result |
| `ReferenceBenchmarks.tableConstruction` | thrpt | 18.6799 ops/ms | 316156.074 B/op | 1000 | `10243311899862777556` | reference result |
| `ReferenceBenchmarks.unionAll` | avgt | 0.0766535 ms/op | 658073.057 B/op | 2000 | `376105377322046160` | reference result |
| `ReferenceBenchmarks.unionAll` | thrpt | 13.1953 ops/ms | 658105.043 B/op | 2000 | `376105377322046160` | reference result |
| `ReferenceBenchmarks.utf8Scan` | avgt | 0.0360935 ms/op | 286256.922 B/op | 1000 | `17333564375837731125` | reference result |
| `ReferenceBenchmarks.utf8Scan` | thrpt | 27.4795 ops/ms | 286256.932 B/op | 1000 | `17333564375837731125` | reference result |
| `SaddleBenchmarks.filterProjectArithmetic` | avgt | 0.0154984 ms/op | 85584.214 B/op | 500 | `17371320548491255848` | semantically equivalent materialized pipeline |
| `SaddleBenchmarks.filterProjectArithmetic` | thrpt | 63.8876 ops/ms | 85584.217 B/op | 500 | `17371320548491255848` | semantically equivalent materialized pipeline |
| `SaddleBenchmarks.groupedLowCardinality` | avgt | 0.0111564 ms/op | 118968.195 B/op | 16 | `0x1.e7cbp15` | comparable grouped reduction shape |
| `SaddleBenchmarks.groupedLowCardinality` | thrpt | 89.9870 ops/ms | 118968.194 B/op | 16 | `0x1.e7cbp15` | comparable grouped reduction shape |
| `SaddleBenchmarks.primitiveScan` | avgt | 0.00106395 ms/op | 0.015 B/op | 1000 | `6204110518321444316` | comparable scan shape |
| `SaddleBenchmarks.primitiveScan` | thrpt | 933.984 ops/ms | 0.015 B/op | 1000 | `6204110518321444316` | comparable scan shape |
| `SpecializedArrayBenchmarks.fusedFilterProjectArithmetic` | avgt | 0.000831355 ms/op | 0.011 B/op | 500 | `17371320548491255848` | specialized lower-bound baseline |
| `SpecializedArrayBenchmarks.fusedFilterProjectArithmetic` | thrpt | 1187.56 ops/ms | 0.011 B/op | 500 | `17371320548491255848` | specialized lower-bound baseline |
| `SpecializedArrayBenchmarks.nullableScan` | avgt | 0.000799058 ms/op | 0.011 B/op | 1000 | `1522408454677155389` | specialized lower-bound baseline |
| `SpecializedArrayBenchmarks.nullableScan` | thrpt | 1230.62 ops/ms | 0.011 B/op | 1000 | `1522408454677155389` | specialized lower-bound baseline |

Raw JMH JSON and console output are in `raw/`. `validation.tsv` records a
single untimed execution of every workload so row counts and checksums travel
with the timing and allocation receipt. `environment.properties` records the
toolchain, hardware description, fixture size, backend, and fallback status.
