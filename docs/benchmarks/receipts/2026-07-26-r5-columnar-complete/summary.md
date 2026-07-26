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
| `ColumnarBenchmarks.antiJoinSparse` | avgt | 0.0453417 ms/op | 162555.888 B/op | 900 | `9254474385373743492` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.antiJoinSparse` | thrpt | 22.7681 ops/ms | 162555.636 B/op | 900 | `9254474385373743492` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.dictionaryScan` | avgt | 0.0235560 ms/op | 53340.214 B/op | 1000 | `17333564375837731125` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.dictionaryScan` | thrpt | 41.3079 ops/ms | 53341.662 B/op | 1000 | `17333564375837731125` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.distinctLowCardinality` | avgt | 0.114198 ms/op | 245083.001 B/op | 334 | `9976595437625738613` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.distinctLowCardinality` | thrpt | 11.1979 ops/ms | 245082.359 B/op | 334 | `9976595437625738613` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.filter` | avgt | 0.0361308 ms/op | 85224.728 B/op | 500 | `12781342423538519465` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.filter` | thrpt | 36.6718 ops/ms | 85208.561 B/op | 500 | `12781342423538519465` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.fusedFilterProjectArithmetic` | avgt | 0.00719336 ms/op | 18552.131 B/op | 500 | `17371320548491255848` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.fusedFilterProjectArithmetic` | thrpt | 154.258 ops/ms | 18664.119 B/op | 500 | `17371320548491255848` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.groupedHighCardinality` | avgt | 0.0664383 ms/op | 217521.949 B/op | 1000 | `17355408869672672305` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.groupedHighCardinality` | thrpt | 16.8193 ops/ms | 217521.767 B/op | 1000 | `17355408869672672305` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.groupedLowCardinality` | avgt | 0.0202361 ms/op | 55096.636 B/op | 16 | `3389499453517600585` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.groupedLowCardinality` | thrpt | 58.2629 ops/ms | 55096.530 B/op | 16 | `3389499453517600585` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinOneToMany` | avgt | 0.102132 ms/op | 382202.739 B/op | 1000 | `8449815210990138133` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinOneToMany` | thrpt | 10.3408 ops/ms | 382202.603 B/op | 1000 | `8449815210990138133` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinOneToOne` | avgt | 0.118854 ms/op | 390395.218 B/op | 1000 | `17280505836941918740` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinOneToOne` | thrpt | 9.40864 ops/ms | 390394.850 B/op | 1000 | `17280505836941918740` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinSkewed` | avgt | 0.112920 ms/op | 362554.930 B/op | 1000 | `6659192972797761300` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinSkewed` | thrpt | 11.0820 ops/ms | 362554.448 B/op | 1000 | `6659192972797761300` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinSparse` | avgt | 0.0192288 ms/op | 49920.528 B/op | 100 | `12312468580073833310` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.joinSparse` | thrpt | 64.7646 ops/ms | 49920.411 B/op | 100 | `12312468580073833310` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.nullableScan` | avgt | 0.00831580 ms/op | 10688.162 B/op | 1000 | `1522408454677155389` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.nullableScan` | thrpt | 133.788 ops/ms | 10688.145 B/op | 1000 | `1522408454677155389` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.primitiveScan` | avgt | 0.00160335 ms/op | 6304.030 B/op | 1000 | `6204110518321444316` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.primitiveScan` | thrpt | 672.542 ops/ms | 6136.028 B/op | 1000 | `6204110518321444316` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.semiJoinSparse` | avgt | 0.0111508 ms/op | 36976.302 B/op | 100 | `10490634394616260580` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.semiJoinSparse` | thrpt | 107.500 ops/ms | 37040.252 B/op | 100 | `10490634394616260580` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.unionAll` | avgt | 0.0145440 ms/op | 27240.372 B/op | 2000 | `376105377322046160` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.unionAll` | thrpt | 79.9631 ops/ms | 27240.320 B/op | 2000 | `376105377322046160` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.utf8Scan` | avgt | 0.0313354 ms/op | 70245.418 B/op | 1000 | `17333564375837731125` | candidate kernel; oracle checksum required |
| `ColumnarBenchmarks.utf8Scan` | thrpt | 50.4365 ops/ms | 70236.603 B/op | 1000 | `17333564375837731125` | candidate kernel; oracle checksum required |
| `ReferenceBenchmarks.antiJoinSparse` | avgt | 4.60629 ms/op | 22936711.779 B/op | 900 | `9254474385373743492` | reference result |
| `ReferenceBenchmarks.antiJoinSparse` | thrpt | 0.246456 ops/ms | 22936601.742 B/op | 900 | `9254474385373743492` | reference result |
| `ReferenceBenchmarks.boundedScalarDecode` | avgt | 0.00306623 ms/op | 14328.077 B/op | 32 | `3392524538599714678` | reference result |
| `ReferenceBenchmarks.boundedScalarDecode` | thrpt | 341.104 ops/ms | 14328.075 B/op | 32 | `3392524538599714678` | reference result |
| `ReferenceBenchmarks.csvDecode` | avgt | 0.407594 ms/op | 741020.716 B/op | 1000 | `29791143` | reference result |
| `ReferenceBenchmarks.csvDecode` | thrpt | 2.98756 ops/ms | 740619.433 B/op | 1000 | `29791143` | reference result |
| `ReferenceBenchmarks.dictionaryScan` | avgt | 0.0420713 ms/op | 152001.086 B/op | 1000 | `17333564375837731125` | reference result |
| `ReferenceBenchmarks.dictionaryScan` | thrpt | 26.1493 ops/ms | 152000.977 B/op | 1000 | `17333564375837731125` | reference result |
| `ReferenceBenchmarks.distinctLowCardinality` | avgt | 0.137734 ms/op | 645032.301 B/op | 334 | `9976595437625738613` | reference result |
| `ReferenceBenchmarks.distinctLowCardinality` | thrpt | 8.69369 ops/ms | 645009.818 B/op | 334 | `9976595437625738613` | reference result |
| `ReferenceBenchmarks.filter` | avgt | 0.165816 ms/op | 879715.217 B/op | 500 | `12781342423538519465` | reference result |
| `ReferenceBenchmarks.filter` | thrpt | 7.07089 ops/ms | 849761.945 B/op | 500 | `12781342423538519465` | reference result |
| `ReferenceBenchmarks.fusedFilterProjectArithmetic` | avgt | 0.141763 ms/op | 862620.394 B/op | 500 | `17371320548491255848` | reference result |
| `ReferenceBenchmarks.fusedFilterProjectArithmetic` | thrpt | 6.98977 ops/ms | 862436.501 B/op | 500 | `17371320548491255848` | reference result |
| `ReferenceBenchmarks.groupedHighCardinality` | avgt | 0.275852 ms/op | 1277255.810 B/op | 1000 | `17355408869672672305` | reference result |
| `ReferenceBenchmarks.groupedHighCardinality` | thrpt | 3.60156 ops/ms | 1277215.050 B/op | 1000 | `17355408869672672305` | reference result |
| `ReferenceBenchmarks.groupedLowCardinality` | avgt | 0.242352 ms/op | 994458.842 B/op | 16 | `3389499453517600585` | reference result |
| `ReferenceBenchmarks.groupedLowCardinality` | thrpt | 4.18593 ops/ms | 994434.296 B/op | 16 | `3389499453517600585` | reference result |
| `ReferenceBenchmarks.joinOneToMany` | avgt | 39.9367 ms/op | 232793862.031 B/op | 1000 | `8449815210990138133` | reference result |
| `ReferenceBenchmarks.joinOneToMany` | thrpt | 0.0250064 ops/ms | 232793861.785 B/op | 1000 | `8449815210990138133` | reference result |
| `ReferenceBenchmarks.joinOneToOne` | avgt | 40.3742 ms/op | 236921622.031 B/op | 1000 | `17280505836941918740` | reference result |
| `ReferenceBenchmarks.joinOneToOne` | thrpt | 0.0248483 ops/ms | 236921620.308 B/op | 1000 | `17280505836941918740` | reference result |
| `ReferenceBenchmarks.joinSkewed` | avgt | 38.9756 ms/op | 222845044.193 B/op | 1000 | `6659192972797761300` | reference result |
| `ReferenceBenchmarks.joinSkewed` | thrpt | 0.0254970 ops/ms | 222845057.327 B/op | 1000 | `6659192972797761300` | reference result |
| `ReferenceBenchmarks.joinSparse` | avgt | 4.49810 ms/op | 23689004.329 B/op | 100 | `12312468580073833310` | reference result |
| `ReferenceBenchmarks.joinSparse` | thrpt | 0.241597 ops/ms | 23688997.062 B/op | 100 | `12312468580073833310` | reference result |
| `ReferenceBenchmarks.nullableScan` | avgt | 0.0881556 ms/op | 467417.213 B/op | 1000 | `1522408454677155389` | reference result |
| `ReferenceBenchmarks.nullableScan` | thrpt | 17.1202 ops/ms | 266904.804 B/op | 1000 | `1522408454677155389` | reference result |
| `ReferenceBenchmarks.primitiveScan` | avgt | 0.0469705 ms/op | 343416.647 B/op | 1000 | `6204110518321444316` | reference result |
| `ReferenceBenchmarks.primitiveScan` | thrpt | 21.2924 ops/ms | 343320.647 B/op | 1000 | `6204110518321444316` | reference result |
| `ReferenceBenchmarks.semiJoinSparse` | avgt | 3.93900 ms/op | 22483067.476 B/op | 100 | `10490634394616260580` | reference result |
| `ReferenceBenchmarks.semiJoinSparse` | thrpt | 0.245933 ops/ms | 22483072.213 B/op | 100 | `10490634394616260580` | reference result |
| `ReferenceBenchmarks.tableConstruction` | avgt | 0.0540697 ms/op | 318961.014 B/op | 1000 | `10243311899862777556` | reference result |
| `ReferenceBenchmarks.tableConstruction` | thrpt | 18.5980 ops/ms | 321196.386 B/op | 1000 | `10243311899862777556` | reference result |
| `ReferenceBenchmarks.unionAll` | avgt | 0.0758089 ms/op | 658073.043 B/op | 2000 | `376105377322046160` | reference result |
| `ReferenceBenchmarks.unionAll` | thrpt | 15.1798 ops/ms | 538248.907 B/op | 2000 | `376105377322046160` | reference result |
| `ReferenceBenchmarks.utf8Scan` | avgt | 0.0362882 ms/op | 286256.934 B/op | 1000 | `17333564375837731125` | reference result |
| `ReferenceBenchmarks.utf8Scan` | thrpt | 25.5911 ops/ms | 278256.996 B/op | 1000 | `17333564375837731125` | reference result |
| `SaddleBenchmarks.filterProjectArithmetic` | avgt | 0.0155267 ms/op | 85584.214 B/op | 500 | `17371320548491255848` | semantically equivalent materialized pipeline |
| `SaddleBenchmarks.filterProjectArithmetic` | thrpt | 64.0629 ops/ms | 85584.215 B/op | 500 | `17371320548491255848` | semantically equivalent materialized pipeline |
| `SaddleBenchmarks.groupedLowCardinality` | avgt | 0.00974839 ms/op | 115896.175 B/op | 16 | `0x1.e7cbp15` | comparable grouped reduction shape |
| `SaddleBenchmarks.groupedLowCardinality` | thrpt | 88.1748 ops/ms | 118972.098 B/op | 16 | `0x1.e7cbp15` | comparable grouped reduction shape |
| `SaddleBenchmarks.primitiveScan` | avgt | 0.00109915 ms/op | 0.015 B/op | 1000 | `6204110518321444316` | comparable scan shape |
| `SaddleBenchmarks.primitiveScan` | thrpt | 908.952 ops/ms | 0.015 B/op | 1000 | `6204110518321444316` | comparable scan shape |
| `SpecializedArrayBenchmarks.fusedFilterProjectArithmetic` | avgt | 0.000873931 ms/op | 0.012 B/op | 500 | `17371320548491255848` | specialized lower-bound baseline |
| `SpecializedArrayBenchmarks.fusedFilterProjectArithmetic` | thrpt | 1171.61 ops/ms | 0.012 B/op | 500 | `17371320548491255848` | specialized lower-bound baseline |
| `SpecializedArrayBenchmarks.nullableScan` | avgt | 0.000831160 ms/op | 0.011 B/op | 1000 | `1522408454677155389` | specialized lower-bound baseline |
| `SpecializedArrayBenchmarks.nullableScan` | thrpt | 1207.96 ops/ms | 0.011 B/op | 1000 | `1522408454677155389` | specialized lower-bound baseline |

Raw JMH JSON and console output are in `raw/`. `validation.tsv` records a
single untimed execution of every workload so row counts and checksums travel
with the timing and allocation receipt. `environment.properties` records the
toolchain, hardware description, fixture size, backend, and fallback status.
