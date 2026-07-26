# R5c Saddle comparison

Decision recorded on 2026-07-26.

The internal columnar candidate wins all three semantically equivalent Saddle
4.0.0-M14 workloads at 1,000 input rows on JDK 21. These results do not change
the public-runtime decision: ordinary `FrameRuntime` execution remains on the
semantic reference interpreter.

| Equivalent materialized workload | frame4s | Saddle | Point win | Conservative win | Allocation reduction |
|---|---:|---:|---:|---:|---:|
| primitive projection | 0.001507 ms | 0.003627 ms | 2.41x | 2.25x | 80.6% |
| fused filter/project arithmetic | 0.006545 ms | 0.015987 ms | 2.44x | 2.25x | 78.2% |
| nullable grouped sum | 0.008800 ms | 0.011333 ms | 1.29x | 1.23x | 83.6% |

The conservative ratio divides Saddle's lower average-time confidence bound
by frame4s's upper bound. All three comparators have identical output
cardinality and checksum; `CourtRunner` fails before timing if either exact
pair disagrees with the semantic oracle.

The earlier lower-bound rows remain published. Saddle's raw array scan takes
0.001113 ms versus 0.001507 ms for frame4s, but does not materialize an output.
Saddle's scalar sum-only grouped reduction takes 0.009920 ms versus 0.016175 ms
for frame4s's four-statistic materialized result, but does not return the same
columns or checksum. Neither is relabeled as an equivalent frame4s loss or
win.

The complete measurement table is in [summary.md](summary.md), exact
cardinalities and checksums are in [validation.tsv](validation.tsv), runtime
provenance is in [environment.properties](environment.properties), and JMH
JSON/log output is under [`raw/`](raw/).
