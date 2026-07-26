# R5 optimized-kernel admission

Decision recorded on 2026-07-26.

- **R5a kernel set: admitted.** Scan/projection, filter/fused projection,
  aggregation, and inner/left join agree with the semantic oracle and satisfy
  every precommitted absolute R2 budget.
- **Public runtime packaging: not admitted for 0.1.0.** The implementation is
  package-internal and the released FS2 runtime remains reference-only. This
  receipt supports kernel-engineering claims, not a claim that ordinary
  `FrameRuntime` calls use the optimized path.
- **R5b kernel set: relative evidence only.** Distinct, union, and semi/anti
  join all clear the relative admission rule, but no absolute ceilings for
  those R4-era workloads were ratified before their implementations were
  observed. No retroactive threshold is invented here.

Every `ColumnarBenchmarks` workload completed without fallback and carries the
same output cardinality and checksum as its `ReferenceBenchmarks` counterpart.
The reusable backend court separately compares schemas, exact scalar values,
raw floating bits, structured failures, and declared ordering on JVM and
Scala.js.

## Absolute R5a budgets

Measurements are full-court average time and normalized allocation at 1,000
input rows. KiB and MiB ceilings use binary units.

| Workload | Time | Ceiling | Allocation | Ceiling | Result |
|---|---:|---:|---:|---:|---|
| primitive scan | 0.001464 ms | 0.010 ms | 6,136 B | 64 KiB | pass |
| nullable scan | 0.007341 ms | 0.010 ms | 10,688 B | 64 KiB | pass |
| UTF-8 scan | 0.005046 ms | 0.030 ms | 22,144 B | 128 KiB | pass |
| dictionary scan | 0.006758 ms | 0.030 ms | 5,320 B | 128 KiB | pass |
| filter | 0.018960 ms | 0.050 ms | 61,280 B | 256 KiB | pass |
| fused filter/project arithmetic | 0.006829 ms | 0.012 ms | 18,552 B | 84 KiB | pass |
| grouped low cardinality | 0.019289 ms | 0.030 ms | 55,073 B | 256 KiB | pass |
| grouped high cardinality | 0.063209 ms | 0.200 ms | 217,538 B | 768 KiB | pass |
| one-to-one join | 0.105184 ms | 0.500 ms | 390,459 B | 2 MiB | pass |
| one-to-many join | 0.096582 ms | 0.500 ms | 382,203 B | 2 MiB | pass |
| sparse join | 0.015623 ms | 0.100 ms | 49,856 B | 512 KiB | pass |
| skewed join | 0.091151 ms | 0.500 ms | 362,490 B | 2 MiB | pass |

The boundary workloads that remain on the reference path also retain their
ratified budgets: CSV decode, table construction, and bounded scalar decode.

## Relative admission and published losses

The conservative ratios below divide the candidate throughput confidence
interval's lower bound by the reference interval's upper bound. They therefore
do not rely on a favorable comparison of point estimates.

| Family/workload | Conservative throughput improvement |
|---|---:|
| primitive scan | 30.4x |
| nullable scan | 9.2x |
| UTF-8 scan | 7.0x |
| dictionary scan | 5.3x |
| filter | 6.6x |
| fused filter/project arithmetic | 19.8x |
| grouped low cardinality | 12.1x |
| grouped high cardinality | 4.3x |
| one-to-one join | 377.7x |
| one-to-many join | 357.7x |
| sparse join | 263.6x |
| skewed join | 383.4x |

On the designated architectural-win workload, the columnar candidate's lower
throughput bound is 148.52 ops/ms and Saddle's upper bound is 67.03 ops/ms:
a conservative 2.21x win. Average allocation is 18,584 B/op versus Saddle's
85,584 B/op, a 78.3% reduction.

The receipt also publishes the losses. Saddle remains about 1.38x faster on
primitive scan average time and 1.73x faster on the comparable
low-cardinality grouped reduction. Those workloads are not represented as
frame4s wins.

## R5b evidence and limitation

The later operations clear the relative rule against the reference path:

| Workload | Candidate lower throughput bound | Reference upper bound | Allocation reduction |
|---|---:|---:|---:|
| distinct, low cardinality | 10.94 ops/ms | 8.68 ops/ms | 61.0% |
| left-semi sparse join | 105.10 ops/ms | 0.260 ops/ms | 99.8% |
| left-anti sparse join | 20.48 ops/ms | 0.281 ops/ms | 99.3% |
| `unionAll` | 79.06 ops/ms | 13.59 ops/ms | 95.9% |

This is sufficient to retain the implementations as internal candidates. It
is not an absolute R5b claim gate because those ceilings did not exist before
the results. A later public backend must ratify user-facing workloads and
ceilings independently of these observed values.

The complete measurement table is in [summary.md](summary.md), exact
cardinalities and checksums in [validation.tsv](validation.tsv), the toolchain
and fallback contract in
[environment.properties](environment.properties), and JMH JSON/log output in
[`raw/`](raw/).
