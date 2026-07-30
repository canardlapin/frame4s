# frame4s benchmark court receipt

Full court receipt using the committed warmup, measurement, fork, heap, and profiler settings.

Scale tier at 1000000 rows. Only the columnar candidate runs.

This tier has no reference oracle, and that is a stated limit rather than an
omission. The semantic reference join is a full nested-loop cross product, so
executing it here would require on the order of 1000000000000
predicate evaluations. Candidate agreement with the reference interpreter is
established by the cross-platform conformance laws and by the small tier, whose
receipts remain the ratified oracle record. Checksums below are candidate
self-consistency values: they detect drift between runs of this tier, and they
are not independent proof of semantic correctness.

Saddle, the specialized-array lower bounds, and the reference rows are absent by
construction, so this receipt ranks nothing against them.

| Benchmark | Mode | Time or throughput | Allocation | Output rows | Checksum | Comparison status |
|---|---:|---:|---:|---:|---:|---|
| `ColumnarBenchmarks.antiJoinSparse` | avgt | 30.8066 ms/op | 41531133.610 B/op | 900000 | `2512695010752838560` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.antiJoinSparse` | thrpt | 0.0296092 ops/ms | 41531284.907 B/op | 900000 | `2512695010752838560` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.dictionaryScan` | avgt | 7.49314 ms/op | 4002976.699 B/op | 1000000 | `5380570838178160033` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.dictionaryScan` | thrpt | 0.122457 ops/ms | 4003007.050 B/op | 1000000 | `5380570838178160033` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.distinctLowCardinality` | avgt | 14.2800 ms/op | 22306821.012 B/op | 333334 | `11583962868550969505` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.distinctLowCardinality` | thrpt | 0.0451899 ops/ms | 22307143.451 B/op | 333334 | `11583962868550969505` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.filter` | avgt | 17.1377 ms/op | 24508489.557 B/op | 500000 | `8226007018004208267` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.filter` | thrpt | 0.0486237 ops/ms | 24508620.461 B/op | 500000 | `8226007018004208267` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.fusedFilterProjectArithmetic` | avgt | 3.16494 ms/op | 8033085.323 B/op | 500000 | `11436329713124332608` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.fusedFilterProjectArithmetic` | thrpt | 0.339766 ops/ms | 8033066.206 B/op | 500000 | `11436329713124332608` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.groupedHighCardinality` | avgt | 42.5515 ms/op | 106325925.672 B/op | 1000000 | `17637657079599010752` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.groupedHighCardinality` | thrpt | 0.0207923 ops/ms | 106326212.800 B/op | 1000000 | `17637657079599010752` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.groupedLowCardinality` | avgt | 10.7437 ms/op | 132879.264 B/op | 16 | `7216036759941470829` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.groupedLowCardinality` | thrpt | 0.0930597 ops/ms | 132881.055 B/op | 16 | `7216036759941470829` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.groupedLowCardinalitySumOnly` | avgt | 8.32195 ms/op | 130330.803 B/op | 16 | `8755585898262683926` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.groupedLowCardinalitySumOnly` | thrpt | 0.123105 ops/ms | 130299.842 B/op | 16 | `8755585898262683926` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.joinOneToMany` | avgt | 93.2419 ms/op | 110731429.227 B/op | 1000000 | `3230237087133107989` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.joinOneToMany` | thrpt | 0.00998925 ops/ms | 110731979.200 B/op | 1000000 | `3230237087133107989` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.joinOneToOne` | avgt | 91.5145 ms/op | 110731432.427 B/op | 1000000 | `6706901494945186080` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.joinOneToOne` | thrpt | 0.00723694 ops/ms | 110733132.160 B/op | 1000000 | `6706901494945186080` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.joinSkewed` | avgt | 3.65015 ms/op | 12104023.923 B/op | 1000 | `6659192972797761300` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.joinSkewed` | thrpt | 0.191522 ops/ms | 12104242.984 B/op | 1000 | `6659192972797761300` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.joinSparse` | avgt | 23.0062 ms/op | 23348258.532 B/op | 100000 | `9974464967841974896` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.joinSparse` | thrpt | 0.0339175 ops/ms | 23348434.895 B/op | 100000 | `9974464967841974896` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.nullableScan` | avgt | 7.49436 ms/op | 8129375.487 B/op | 1000000 | `11112288120000694880` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.nullableScan` | thrpt | 0.124251 ops/ms | 8129388.266 B/op | 1000000 | `11112288120000694880` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.primitiveScan` | avgt | 2.23407 ms/op | 4003807.122 B/op | 1000000 | `5984889344555131744` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.primitiveScan` | thrpt | 0.709054 ops/ms | 4003783.629 B/op | 1000000 | `5984889344555131744` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.semiJoinSparse` | avgt | 20.7109 ms/op | 20450583.783 B/op | 100000 | `5135246031684690592` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.semiJoinSparse` | thrpt | 0.0477977 ops/ms | 20450587.752 B/op | 100000 | `5135246031684690592` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.unionAll` | avgt | 10.7087 ms/op | 24006563.200 B/op | 2000000 | `7649885274515455104` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.unionAll` | thrpt | 0.0916771 ops/ms | 24006562.417 B/op | 2000000 | `7649885274515455104` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.utf8Scan` | avgt | 5.13331 ms/op | 6376680.860 B/op | 1000000 | `5380570838178160033` | candidate kernel; self-consistency only, no oracle at this tier |
| `ColumnarBenchmarks.utf8Scan` | thrpt | 0.192673 ops/ms | 6376690.635 B/op | 1000000 | `5380570838178160033` | candidate kernel; self-consistency only, no oracle at this tier |

Raw JMH JSON and console output are in `raw/`. `validation.tsv` records a
single untimed execution of every workload so row counts and checksums travel
with the timing and allocation receipt. `environment.properties` records the
toolchain, hardware description, fixture size, backend, and fallback status.
