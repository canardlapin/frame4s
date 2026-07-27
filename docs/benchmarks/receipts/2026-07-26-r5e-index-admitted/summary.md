# frame4s secondary-index admission

Full admission receipt. The candidate passes the
precommitted direct-lookup gates. Exact values and source order were checked
against the linear scan before timing.

## Warm lookup and cold construction

| Workload | Rows | Frozen scan ms/op | Indexed ms/op | Speedup | Build ms | Break-even queries | Gate | Result |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| single | 100000 | 0.014950 | 0.000012 | 1289.06x | 1.083662 | 72.54 | reported | pass |
| single | 1000000 | 0.132589 | 0.000015 | 8685.26x | 15.989711 | 120.61 | >= 2.00x | pass |
| batch32 | 100000 | 0.487895 | 0.000291 | 1678.52x | 1.083662 | 2.22 | >= 2.00x | pass |
| batch32 | 1000000 | 5.111050 | 0.000310 | 16496.66x | 15.989711 | 3.13 | >= 5.00x | pass |

Break-even is `build_ms / (scan_ms - indexed_ms)` and therefore includes
index construction rather than presenting warm lookup alone.

## Owned memory

| Rows | Owned bytes | Bytes/row | Gate | Result |
|---|---:|---:|---:|---:|
| 100000 | 2497152 | 24.972 | reported | pass |
| 1000000 | 20777216 | 20.777 | <= 24.000 | pass |

## Raw candidate measurements

| Benchmark | Input rows | Mode | Time or throughput | Allocation | Output rows | Checksum or owned bytes |
|---|---:|---:|---:|---:|---:|---:|
| `IndexBuildCourt.build` | 100000 | avgt | 1.08366 ms/op | 2497582.641 B/op | 100000 | `2497152` |
| `IndexBuildCourt.build` | 1000000 | avgt | 15.9897 ms/op | 20778155.612 B/op | 1000000 | `20777216` |
| `IndexLookupCourt.batch32Lookup` | 100000 | avgt | 0.000290670 ms/op | 616.010 B/op | 32 | `13590724320720048604` |
| `IndexLookupCourt.batch32Lookup` | 100000 | thrpt | 3448.76 ops/ms | 616.010 B/op | 32 | `13590724320720048604` |
| `IndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000309823 ms/op | 616.011 B/op | 32 | `14554966086508252864` |
| `IndexLookupCourt.batch32Lookup` | 1000000 | thrpt | 3236.10 ops/ms | 616.011 B/op | 32 | `14554966086508252864` |
| `IndexLookupCourt.singleLookup` | 100000 | avgt | 1.15976e-05 ms/op | 72.000 B/op | 1 | `100030` |
| `IndexLookupCourt.singleLookup` | 100000 | thrpt | 87408.1 ops/ms | 72.000 B/op | 1 | `100030` |
| `IndexLookupCourt.singleLookup` | 1000000 | avgt | 1.52660e-05 ms/op | 72.001 B/op | 1 | `1000030` |
| `IndexLookupCourt.singleLookup` | 1000000 | thrpt | 64748.3 ops/ms | 72.001 B/op | 1 | `1000030` |

Raw JMH output is under `raw/`; `validation.tsv` and
`environment.properties` carry semantic and runtime provenance. Baseline
scores come from `2026-07-26-r5e-index-baseline/summary.md`.
