# frame4s secondary-index layout court

Full R5f layout admission receipt. The explicit compact layout passes the
precommitted speed, memory, and fast-path gates. Both layouts were checked
against the same frozen stable scan before timing.

## Warm lookup and cold construction

| Layout | Workload | Rows | Frozen scan ms/op | Index ms/op | Speedup | Build ms | Break-even queries | Gate | Result |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|
| FastHash | single | 100000 | 0.015470 | 0.000007 | 2177.08x | 1.565114 | 101.22 | reported | pass |
| FastHash | single | 1000000 | 0.159515 | 0.000009 | 18511.66x | 23.761021 | 148.97 | reported | pass |
| FastHash | batch32 | 100000 | 0.598596 | 0.000365 | 1641.72x | 1.565114 | 2.62 | reported | pass |
| FastHash | batch32 | 1000000 | 6.012830 | 0.000385 | 15623.02x | 23.761021 | 3.95 | reported | pass |
| CompactSorted | single | 100000 | 0.015470 | 0.000015 | 1032.24x | 1.322002 | 85.54 | reported | pass |
| CompactSorted | single | 1000000 | 0.159515 | 0.000018 | 8760.86x | 22.024373 | 138.09 | >= 2.00x | pass |
| CompactSorted | batch32 | 100000 | 0.598596 | 0.001328 | 450.83x | 1.322002 | 2.21 | >= 2.00x | pass |
| CompactSorted | batch32 | 1000000 | 6.012830 | 0.001521 | 3952.22x | 22.024373 | 3.66 | >= 5.00x | pass |

Break-even is `build_ms / (scan_ms - index_ms)`. It includes index
construction and is reported separately for each layout.

## Owned memory

| Layout | Rows | Owned bytes | Bytes/source row | Reduction versus FastHash | Result |
|---|---:|---:|---:|---:|---:|
| FastHash | 100000 | 2497152 | 24.972 | control | reported |
| CompactSorted | 100000 | 800000 | 8.000 | 67.96% | pass |
| FastHash | 1000000 | 20777216 | 20.777 | control | reported |
| CompactSorted | 1000000 | 8000000 | 8.000 | 61.50% | pass |

At 1,000,000 rows `CompactSorted` must use at most 12 bytes/source row
and at least 40% less owned memory than `FastHash`.

## FastHash single-key guard

| Measure | Result | Gate | Status |
|---|---:|---:|---:|
| Direct one-key latency | 0.000008617 ms/op | >= 1.25x faster than same-run former path | pass |
| Former batch-compatible latency | 0.000020013 ms/op | same-run control (2.32x direct speedup) | reported |
| Normalized allocation | 0.001 B/op | <= 72.000 B/op | pass |
| Former-path allocation | 96.001 B/op | same-run control | reported |
| Prior R5e direct point | 0.000015266 ms/op | descriptive only; different JVM | reported |

## Raw candidate measurements

| Benchmark | Input rows | Mode | Time or throughput | Allocation | Output rows | Checksum or owned bytes |
|---|---:|---:|---:|---:|---:|---:|
| `CompactIndexBuildCourt.build` | 100000 | avgt | 1.32200 ms/op | 1601894.583 B/op | 100000 | `800000` |
| `CompactIndexBuildCourt.build` | 1000000 | avgt | 22.0244 ms/op | 16003304.106 B/op | 1000000 | `8000000` |
| `CompactIndexLookupCourt.batch32Lookup` | 100000 | avgt | 0.00132777 ms/op | 616.090 B/op | 32 | `13590724320720048604` |
| `CompactIndexLookupCourt.batch32Lookup` | 100000 | thrpt | 739.313 ops/ms | 616.095 B/op | 32 | `13590724320720048604` |
| `CompactIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00152138 ms/op | 616.104 B/op | 32 | `14554966086508252864` |
| `CompactIndexLookupCourt.batch32Lookup` | 1000000 | thrpt | 654.908 ops/ms | 616.106 B/op | 32 | `14554966086508252864` |
| `CompactIndexLookupCourt.singleLookup` | 100000 | avgt | 1.49872e-05 ms/op | 0.001 B/op | 1 | `100030` |
| `CompactIndexLookupCourt.singleLookup` | 100000 | thrpt | 66701.4 ops/ms | 0.001 B/op | 1 | `100030` |
| `CompactIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.82077e-05 ms/op | 0.001 B/op | 1 | `1000030` |
| `CompactIndexLookupCourt.singleLookup` | 1000000 | thrpt | 57723.8 ops/ms | 0.001 B/op | 1 | `1000030` |
| `IndexBuildCourt.build` | 100000 | avgt | 1.56511 ms/op | 2498005.209 B/op | 100000 | `2497152` |
| `IndexBuildCourt.build` | 1000000 | avgt | 23.7610 ms/op | 20779653.205 B/op | 1000000 | `20777216` |
| `IndexLegacyControlCourt.singleLookup` | 100000 | avgt | 1.21075e-05 ms/op | 48.001 B/op | 1 | `100030` |
| `IndexLegacyControlCourt.singleLookup` | 1000000 | avgt | 2.00130e-05 ms/op | 96.001 B/op | 1 | `1000030` |
| `IndexLookupCourt.batch32Lookup` | 100000 | avgt | 0.000364615 ms/op | 616.027 B/op | 32 | `13590724320720048604` |
| `IndexLookupCourt.batch32Lookup` | 100000 | thrpt | 2806.71 ops/ms | 616.027 B/op | 32 | `13590724320720048604` |
| `IndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000384870 ms/op | 616.028 B/op | 32 | `14554966086508252864` |
| `IndexLookupCourt.batch32Lookup` | 1000000 | thrpt | 2612.70 ops/ms | 616.027 B/op | 32 | `14554966086508252864` |
| `IndexLookupCourt.singleLookup` | 100000 | avgt | 7.10599e-06 ms/op | 0.000 B/op | 1 | `100030` |
| `IndexLookupCourt.singleLookup` | 100000 | thrpt | 172910 ops/ms | 0.000 B/op | 1 | `100030` |
| `IndexLookupCourt.singleLookup` | 1000000 | avgt | 8.61700e-06 ms/op | 0.001 B/op | 1 | `1000030` |
| `IndexLookupCourt.singleLookup` | 1000000 | thrpt | 148374 ops/ms | 0.000 B/op | 1 | `1000030` |

Raw JMH output is under `raw/`; `validation.tsv` and
`environment.properties` carry semantic and runtime provenance. Baseline
scores come from `baseline/summary.md`.
