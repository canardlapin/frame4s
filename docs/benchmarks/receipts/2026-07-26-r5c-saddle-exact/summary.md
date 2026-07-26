# frame4s benchmark court receipt

Full court receipt using the committed warmup, measurement, fork, heap, and profiler settings.

The semantic reference interpreter is an executable oracle, not the optimized backend.
Every `ColumnarBenchmarks` row refuses fallback and must carry the same checksum and
output cardinality as its corresponding `ReferenceBenchmarks` row.
The materialized primitive projection, fused filter/project, and materialized nullable
grouped-sum Saddle rows are comparable. The raw primitive scan and scalar grouped
reduction remain explicit lower bounds, not win/loss comparators. SQL duplicate-key
joins, dictionary layout, CSV acquisition, and owned-table construction have no claimed
Saddle-equivalent result. Scautable is intentionally excluded from relational rankings.

| Benchmark | Mode | Time or throughput | Allocation | Output rows | Checksum | Comparison status |
|---|---:|---:|---:|---:|---:|---|
| `ColumnarBenchmarks.antiJoinSparse` | avgt | 0.0413203 ms/op | 162555.531 B/op | 900 | `9254474385373743492` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.antiJoinSparse` | thrpt | 22.6790 ops/ms | 162553.174 B/op | 900 | `9254474385373743492` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.dictionaryScan` | avgt | 0.00748140 ms/op | 5328.195 B/op | 1000 | `17333564375837731125` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.dictionaryScan` | thrpt | 104.882 ops/ms | 5335.661 B/op | 1000 | `17333564375837731125` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.distinctLowCardinality` | avgt | 0.0848509 ms/op | 221082.237 B/op | 334 | `9976595437625738613` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.distinctLowCardinality` | thrpt | 9.35200 ops/ms | 245082.875 B/op | 334 | `9976595437625738613` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.filter` | avgt | 0.0194600 ms/op | 61336.401 B/op | 500 | `12781342423538519465` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.filter` | thrpt | 48.6718 ops/ms | 61232.425 B/op | 500 | `12781342423538519465` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.fusedFilterProjectArithmetic` | avgt | 0.00654502 ms/op | 18664.120 B/op | 500 | `17371320548491255848` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.fusedFilterProjectArithmetic` | thrpt | 128.956 ops/ms | 18584.142 B/op | 500 | `17371320548491255848` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.groupedHighCardinality` | avgt | 0.0638466 ms/op | 236905.902 B/op | 1000 | `17355408869672672305` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.groupedHighCardinality` | thrpt | 16.3386 ops/ms | 236641.848 B/op | 1000 | `17355408869672672305` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.groupedLowCardinality` | avgt | 0.0161750 ms/op | 38992.478 B/op | 16 | `3389499453517600585` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.groupedLowCardinality` | thrpt | 61.5679 ops/ms | 39040.487 B/op | 16 | `3389499453517600585` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.groupedLowCardinalitySumOnly` | avgt | 0.00879969 ms/op | 19296.246 B/op | 16 | `3392807513338011926` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.groupedLowCardinalitySumOnly` | thrpt | 113.325 ops/ms | 19296.247 B/op | 16 | `3392807513338011926` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinOneToMany` | avgt | 0.0983020 ms/op | 382266.654 B/op | 1000 | `8449815210990138133` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinOneToMany` | thrpt | 9.63671 ops/ms | 382202.775 B/op | 1000 | `8449815210990138133` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinOneToOne` | avgt | 0.104479 ms/op | 390458.856 B/op | 1000 | `17280505836941918740` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinOneToOne` | thrpt | 8.94452 ops/ms | 390394.956 B/op | 1000 | `17280505836941918740` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinSkewed` | avgt | 0.0944630 ms/op | 362490.540 B/op | 1000 | `6659192972797761300` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinSkewed` | thrpt | 10.6169 ops/ms | 362490.517 B/op | 1000 | `6659192972797761300` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinSparse` | avgt | 0.0155286 ms/op | 49856.414 B/op | 100 | `12312468580073833310` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinSparse` | thrpt | 60.9684 ops/ms | 49856.430 B/op | 100 | `12312468580073833310` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.nullableScan` | avgt | 0.00768239 ms/op | 10688.149 B/op | 1000 | `1522408454677155389` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.nullableScan` | thrpt | 132.346 ops/ms | 10688.148 B/op | 1000 | `1522408454677155389` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.primitiveScan` | avgt | 0.00150743 ms/op | 6208.028 B/op | 1000 | `6204110518321444316` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.primitiveScan` | thrpt | 640.983 ops/ms | 6136.029 B/op | 1000 | `6204110518321444316` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.semiJoinSparse` | avgt | 0.0105651 ms/op | 36976.297 B/op | 100 | `10490634394616260580` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.semiJoinSparse` | thrpt | 102.668 ops/ms | 37040.267 B/op | 100 | `10490634394616260580` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.unionAll` | avgt | 0.0128154 ms/op | 27240.328 B/op | 2000 | `376105377322046160` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.unionAll` | thrpt | 75.5536 ops/ms | 27240.333 B/op | 2000 | `376105377322046160` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.utf8Scan` | avgt | 0.00540365 ms/op | 22152.152 B/op | 1000 | `17333564375837731125` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.utf8Scan` | thrpt | 178.592 ops/ms | 22152.156 B/op | 1000 | `17333564375837731125` | candidate kernel; oracle checksum required |
| `ReferenceBenchmarks.antiJoinSparse` | avgt | 3.94958 ms/op | 22922181.119 B/op | 900 | `9254474385373743492` | reference result |
| `ReferenceBenchmarks.antiJoinSparse` | thrpt | 0.225874 ops/ms | 22936622.177 B/op | 900 | `9254474385373743492` | reference result |
| `ReferenceBenchmarks.boundedScalarDecode` | avgt | 0.00286864 ms/op | 14328.073 B/op | 32 | `3392524538599714678` | reference result |
| `ReferenceBenchmarks.boundedScalarDecode` | thrpt | 341.452 ops/ms | 14328.075 B/op | 32 | `3392524538599714678` | reference result |
| `ReferenceBenchmarks.csvDecode` | avgt | 0.323406 ms/op | 740291.925 B/op | 1000 | `29791143` | reference result |
| `ReferenceBenchmarks.csvDecode` | thrpt | 2.73700 ops/ms | 740597.927 B/op | 1000 | `29791143` | reference result |
| `ReferenceBenchmarks.dictionaryScan` | avgt | 0.0394900 ms/op | 152001.008 B/op | 1000 | `17333564375837731125` | reference result |
| `ReferenceBenchmarks.dictionaryScan` | thrpt | 20.2937 ops/ms | 152001.188 B/op | 1000 | `17333564375837731125` | reference result |
| `ReferenceBenchmarks.distinctLowCardinality` | avgt | 0.113616 ms/op | 644993.789 B/op | 334 | `9976595437625738613` | reference result |
| `ReferenceBenchmarks.distinctLowCardinality` | thrpt | 8.68654 ops/ms | 644993.809 B/op | 334 | `9976595437625738613` | reference result |
| `ReferenceBenchmarks.filter` | avgt | 0.146452 ms/op | 849402.016 B/op | 500 | `12781342423538519465` | reference result |
| `ReferenceBenchmarks.filter` | thrpt | 7.03955 ops/ms | 849761.960 B/op | 500 | `12781342423538519465` | reference result |
| `ReferenceBenchmarks.fusedFilterProjectArithmetic` | avgt | 0.147993 ms/op | 893850.619 B/op | 500 | `17371320548491255848` | reference result |
| `ReferenceBenchmarks.fusedFilterProjectArithmetic` | thrpt | 6.99145 ops/ms | 894385.969 B/op | 500 | `17371320548491255848` | reference result |
| `ReferenceBenchmarks.groupedHighCardinality` | avgt | 0.277761 ms/op | 1277223.884 B/op | 1000 | `17355408869672672305` | reference result |
| `ReferenceBenchmarks.groupedHighCardinality` | thrpt | 3.73672 ops/ms | 1277166.494 B/op | 1000 | `17355408869672672305` | reference result |
| `ReferenceBenchmarks.groupedLowCardinality` | avgt | 0.250238 ms/op | 994504.969 B/op | 16 | `3389499453517600585` | reference result |
| `ReferenceBenchmarks.groupedLowCardinality` | thrpt | 4.10887 ops/ms | 1092516.198 B/op | 16 | `3389499453517600585` | reference result |
| `ReferenceBenchmarks.groupedLowCardinalitySumOnly` | avgt | 0.161103 ms/op | 616565.110 B/op | 16 | `3392807513338011926` | reference result |
| `ReferenceBenchmarks.groupedLowCardinalitySumOnly` | thrpt | 6.47340 ops/ms | 616658.349 B/op | 16 | `3392807513338011926` | reference result |
| `ReferenceBenchmarks.joinOneToMany` | avgt | 40.1220 ms/op | 232793864.369 B/op | 1000 | `8449815210990138133` | reference result |
| `ReferenceBenchmarks.joinOneToMany` | thrpt | 0.0251605 ops/ms | 232793860.062 B/op | 1000 | `8449815210990138133` | reference result |
| `ReferenceBenchmarks.joinOneToOne` | avgt | 40.1105 ms/op | 236921623.015 B/op | 1000 | `17280505836941918740` | reference result |
| `ReferenceBenchmarks.joinOneToOne` | thrpt | 0.0248212 ops/ms | 236921622.523 B/op | 1000 | `17280505836941918740` | reference result |
| `ReferenceBenchmarks.joinSkewed` | avgt | 43.0471 ms/op | 222845105.442 B/op | 1000 | `6659192972797761300` | reference result |
| `ReferenceBenchmarks.joinSkewed` | thrpt | 0.0260713 ops/ms | 222845038.743 B/op | 1000 | `6659192972797761300` | reference result |
| `ReferenceBenchmarks.joinSparse` | avgt | 5.50758 ms/op | 23689145.554 B/op | 100 | `12312468580073833310` | reference result |
| `ReferenceBenchmarks.joinSparse` | thrpt | 0.245862 ops/ms | 23688984.397 B/op | 100 | `12312468580073833310` | reference result |
| `ReferenceBenchmarks.nullableScan` | avgt | 0.0913630 ms/op | 467361.256 B/op | 1000 | `1522408454677155389` | reference result |
| `ReferenceBenchmarks.nullableScan` | thrpt | 12.1564 ops/ms | 467513.134 B/op | 1000 | `1522408454677155389` | reference result |
| `ReferenceBenchmarks.primitiveScan` | avgt | 0.110414 ms/op | 343225.478 B/op | 1000 | `6204110518321444316` | reference result |
| `ReferenceBenchmarks.primitiveScan` | thrpt | 21.6046 ops/ms | 343352.640 B/op | 1000 | `6204110518321444316` | reference result |
| `ReferenceBenchmarks.semiJoinSparse` | avgt | 3.94455 ms/op | 22483058.732 B/op | 100 | `10490634394616260580` | reference result |
| `ReferenceBenchmarks.semiJoinSparse` | thrpt | 0.256647 ops/ms | 22483055.936 B/op | 100 | `10490634394616260580` | reference result |
| `ReferenceBenchmarks.tableConstruction` | avgt | 0.0541735 ms/op | 321617.265 B/op | 1000 | `10243311899862777556` | reference result |
| `ReferenceBenchmarks.tableConstruction` | thrpt | 18.8628 ops/ms | 319226.628 B/op | 1000 | `10243311899862777556` | reference result |
| `ReferenceBenchmarks.unionAll` | avgt | 0.0673214 ms/op | 538192.930 B/op | 2000 | `376105377322046160` | reference result |
| `ReferenceBenchmarks.unionAll` | thrpt | 12.8621 ops/ms | 658089.072 B/op | 2000 | `376105377322046160` | reference result |
| `ReferenceBenchmarks.utf8Scan` | avgt | 0.0392881 ms/op | 278257.004 B/op | 1000 | `17333564375837731125` | reference result |
| `ReferenceBenchmarks.utf8Scan` | thrpt | 27.3779 ops/ms | 286256.934 B/op | 1000 | `17333564375837731125` | reference result |
| `SaddleBenchmarks.filterProjectArithmetic` | avgt | 0.0159871 ms/op | 85584.221 B/op | 500 | `17371320548491255848` | semantically equivalent materialized pipeline |
| `SaddleBenchmarks.filterProjectArithmetic` | thrpt | 63.1383 ops/ms | 85584.219 B/op | 500 | `17371320548491255848` | semantically equivalent materialized pipeline |
| `SaddleBenchmarks.groupedLowCardinality` | avgt | 0.00992026 ms/op | 115896.177 B/op | 16 | `0x1.e7cbp15` | sum-only scalar grouped-reduction lower bound |
| `SaddleBenchmarks.groupedLowCardinality` | thrpt | 95.3403 ops/ms | 117432.185 B/op | 16 | `0x1.e7cbp15` | sum-only scalar grouped-reduction lower bound |
| `SaddleBenchmarks.groupedLowCardinalitySumOnly` | avgt | 0.0113332 ms/op | 117432.197 B/op | 16 | `3392807513338011926` | semantically equivalent materialized group-key and sum output |
| `SaddleBenchmarks.groupedLowCardinalitySumOnly` | thrpt | 81.9280 ops/ms | 119084.092 B/op | 16 | `3392807513338011926` | semantically equivalent materialized group-key and sum output |
| `SaddleBenchmarks.primitiveMaterializedProjection` | avgt | 0.00362717 ms/op | 31976.050 B/op | 1000 | `6204110518321444316` | semantically equivalent materialized primitive projection |
| `SaddleBenchmarks.primitiveMaterializedProjection` | thrpt | 277.560 ops/ms | 31976.049 B/op | 1000 | `6204110518321444316` | semantically equivalent materialized primitive projection |
| `SaddleBenchmarks.primitiveScan` | avgt | 0.00111288 ms/op | 0.015 B/op | 1000 | `6204110518321444316` | raw scan lower bound; no output materialization |
| `SaddleBenchmarks.primitiveScan` | thrpt | 932.810 ops/ms | 0.015 B/op | 1000 | `6204110518321444316` | raw scan lower bound; no output materialization |
| `SpecializedArrayBenchmarks.fusedFilterProjectArithmetic` | avgt | 0.000861780 ms/op | 0.012 B/op | 500 | `17371320548491255848` | specialized lower-bound baseline |
| `SpecializedArrayBenchmarks.fusedFilterProjectArithmetic` | thrpt | 1175.38 ops/ms | 0.012 B/op | 500 | `17371320548491255848` | specialized lower-bound baseline |
| `SpecializedArrayBenchmarks.nullableScan` | avgt | 0.000813630 ms/op | 0.011 B/op | 1000 | `1522408454677155389` | specialized lower-bound baseline |
| `SpecializedArrayBenchmarks.nullableScan` | thrpt | 1231.15 ops/ms | 0.011 B/op | 1000 | `1522408454677155389` | specialized lower-bound baseline |

Raw JMH JSON and console output are in `raw/`. `validation.tsv` records a
single untimed execution of every workload so row counts and checksums travel
with the timing and allocation receipt. `environment.properties` records the
toolchain, hardware description, fixture size, backend, and fallback status.
