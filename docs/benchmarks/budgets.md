# Ratified performance budgets

Ratified on 2026-07-26 before R5 optimization, using the full
[R2 reference receipt](receipts/2026-07-26-r2-reference/summary.md).

These are claim gates, not a requirement for the factual `0.1.0` release.
frame4s may publish the reference interpreter with honest receipts and no
comparative speed claim. An optimized backend or comparative claim must meet
the rules below.

## Admission rule

For the first kernel in a family, the semantic reference result is the previous
best frame4s path. Thereafter, the comparison is against the fastest already
admitted frame4s path for that exact workload and checksum.

A kernel is admitted only when:

1. the reusable oracle-conformance laws agree on schema, rows, structured
   errors, and declared ordering;
2. the primary benchmark's lower confidence bound improves throughput by at
   least 20%, or allocation by at least 25%, against the previous best
   frame4s path;
3. the other measured dimension does not regress by more than 5% without a
   written workload-specific justification; and
4. its result, fallback receipt, and before/after raw data are committed.

Clearing that relative bar admits an implementation stage. Meeting the
absolute budgets below admits the corresponding performance claim.

## Absolute budgets

All values are per operation at 1,000 input rows, one thread, JDK 21, a 1 GiB
benchmark heap, and the fixture/checksum contract in the R2 receipt.

| Workload | Maximum average time | Maximum allocation |
|---|---:|---:|
| primitive scan | 0.010 ms | 64 KiB |
| nullable scan | 0.010 ms | 64 KiB |
| UTF-8 scan | 0.030 ms | 128 KiB |
| dictionary scan | 0.030 ms | 128 KiB |
| filter | 0.050 ms | 256 KiB |
| fused filter/project arithmetic | 0.012 ms | 84 KiB |
| grouped low cardinality | 0.030 ms | 256 KiB |
| grouped high cardinality | 0.200 ms | 768 KiB |
| one-to-one join | 0.500 ms | 2 MiB |
| one-to-many join | 0.500 ms | 2 MiB |
| sparse join | 0.100 ms | 512 KiB |
| skewed join | 0.500 ms | 2 MiB |
| CSV decode | 0.400 ms | 768 KiB |
| table construction | 0.080 ms | 400 KiB |
| bounded 32-row scalar decode | 0.005 ms | 20 KiB |

The CSV, construction, and bounded-read budgets are already met by the
reference path. That does not turn the reference interpreter into a production
engine; it simply prevents an optimized backend from regressing working
boundary code.

## Baseline ratios and losses

The full receipt records these average-time and allocation comparisons:

| Shape | Reference versus Saddle time | Reference versus Saddle allocation |
|---|---:|---:|
| primitive scan | 43.86x slower | Saddle result is at profiler noise floor; ratio omitted |
| fused filter/project arithmetic | 9.88x slower | 10.06x more |
| grouped low-cardinality reduction | 21.38x slower | 8.35x more |

The best existing frame4s ratio is 1.00 by definition: there is no optimized
path yet. Join losses are even larger in absolute terms—about 39 ms and
223–237 MiB per 1,000-row dense/skewed operation—and are reported without a
fabricated Saddle comparator.

## Designated architectural win

`fusedFilterProjectArithmetic` is the architectural-win workload. It filters
on `id`, projects `id` and checked `id + 1`, materializes 500 rows, and produces
the same checksum in frame4s, Saddle, and the specialized-array baseline.

It is designated because a logical plan can fuse filter, checked arithmetic,
and projection into one columnar pass; the result is not explained by a CSV
parser, a null-policy mismatch, or a different output contract.

The comparative win requires both:

- average time at or below 0.012 ms, more than 20% faster than the ratified
  Saddle result of 0.0155019 ms; and
- allocation at or below 84 KiB, below Saddle's ratified 85,584 B/op.

Changing this workload or either threshold after observing an R5 result
requires a new dated rationale and preserved before/after receipts.

## R5 outcome

The full
[R5 admission receipt](receipts/2026-07-26-r5-columnar-admitted-final/admission.md)
records every pass and loss. All R5a workloads satisfy these absolute budgets,
and the designated fused pipeline wins against the equivalent Saddle workload.
The preceding
[failed receipt](receipts/2026-07-26-r5-columnar-complete/admission.md) is
preserved: UTF-8 scan initially missed its time ceiling, and the ceiling was
not changed.

Distinct, union, and semi/anti join were added after these R2 budgets were
ratified. Their strong relative results are reported, but no retroactive
absolute threshold or public performance claim is manufactured for them.

## R5c exact-comparator follow-up

The later
[R5c Saddle receipt](receipts/2026-07-26-r5c-saddle-exact/comparison.md)
preserves the raw lower bounds and adds exact materialized comparators.
The internal columnar candidate wins primitive projection, fused
filter/project, and nullable grouped sum conservatively, without changing the
ratified fixtures or thresholds. A
[separate Pandas receipt](receipts/2026-07-26-r5c-pandas/summary.md)
reports cross-runtime timings and their validation limits; those descriptive
ratios are not retroactively promoted into JMH claim gates.

## R5e secondary-index gates

R5e is governed by
[ADR 0003](../design/adr-0003-secondary-index.md). Before the index
implementation is measured, the dedicated court records exact single-key and
32-key lower-bound scans at 100,000 and 1,000,000 rows.

The candidate must preserve exact source-order output and clear these
precommitted gates:

| Workload | Required warm improvement |
|---|---:|
| 100,000 rows, 32 keys | 2x |
| 1,000,000 rows, one key | 2x |
| 1,000,000 rows, 32 keys | 5x |

At 1,000,000 rows, owned index arrays must use at most 24 bytes per source
row. Cold construction, warm lookup, normalized allocation, and calculated
break-even query counts are all published. These are internal-kernel admission
gates, not a public `FrameRuntime` performance claim.

The
[R5e direct-index receipt](receipts/2026-07-26-r5e-index-admitted/summary.md)
passes these gates: 8,685x single-key and 16,497x 32-key improvement at
1,000,000 rows, 20.777 owned bytes per row, and respective break-even counts
of 120.61 and 3.13 queries. The separate
[prepared-join receipt](receipts/2026-07-26-r5e-prepared-join/summary.md)
does not generally admit join reuse: only the sparse fixture improves both
warm and cold, while one-to-one, one-to-many, and skewed joins regress.

## R5f secondary-index layout gates

R5f keeps the admitted hash layout as the speed control and tests the explicit
`CompactSorted` layout described in
[ADR 0003](../design/adr-0003-secondary-index.md). It reuses the frozen R5e
scan fixtures, query keys, checksums, and sizes and re-executes them in the
same runtime as both layouts.

Before implementation, the compact candidate is required to:

| Measure at 1,000,000 source rows | Required result |
|---|---:|
| Owned arrays | <= 12 bytes/source row |
| Owned arrays versus `FastHash` | >= 40% reduction |
| Warm one-key lookup versus scan | >= 2x faster |
| Warm 32-key lookup versus scan | >= 5x faster |

The optimized `FastHash` one-key path must be at least 1.25x faster than the
same-run former batch-compatible one-key path and allocate no more than 72
normalized bytes per operation. The earlier R5e timing is descriptive because
it was captured on another JVM. Both layouts must publish construction,
lookup, allocation, owned bytes, and break-even counts and pass the
cross-platform semantic and metamorphic laws in ADR 0003.

A denser open-addressed hash is rejected analytically: at a 0.75 load factor,
its three required `Int32` arrays have a 14.67-byte/source-row lower bound
before object overhead, so it cannot meet the 12-byte gate. This rejection is
part of the precommitted court rather than a post-benchmark omission.

The
[full R5f receipt](receipts/2026-07-26-r5f-index-layouts/summary.md)
passes all gates. At 1,000,000 rows, `CompactSorted` uses 8.000 bytes/source
row, 61.50% less than `FastHash`, while remaining 8,761x faster than the
single-key scan and 3,952x faster than the 32-key scan. The direct `FastHash`
path is 2.32x faster than the former path and reports 0.001 B/op versus
96.001 B/op. `FastHash` remains the explicit default; `CompactSorted` is
admitted as the memory-oriented choice.

## R5g packed and balanced-layout gates

R5g preserves the R5f receipt as its speed/allocation control and widens the
court before judging `PackedSorted`, the optimized batch engine, or
`FlatHashRows`.

| Candidate at 1,000,000 non-null source rows | Required result |
|---|---:|
| `PackedSorted` owned arrays | <= 6.75 bytes/source row |
| `PackedSorted` versus `CompactSorted` memory | >= 15% reduction |
| Optimized `FastHash` batch32 allocation | <= 500 B/op |
| Optimized `CompactSorted` batch32 allocation | <= 500 B/op |
| Either optimized control batch32 latency | <= 1.10x frozen R5f point |
| `FlatHashRows` owned arrays | <= 12 bytes/source row |
| `FlatHashRows` unique one-key versus `CompactSorted` | >= 1.25x faster |
| `FlatHashRows` unique batch32 versus `CompactSorted` | >= 1.25x faster |

The frozen R5f latency points are 0.000384870 ms/op for `FastHash` batch32 and
0.00152138 ms/op for `CompactSorted` batch32. They are guardrails, not claims
that different JDKs or hosts are directly rankable; the R5g receipt must also
publish same-runtime controls. A cross-runtime miss is reported separately
from a same-runtime regression.

The wider court adds exact unique-hit, mixed-hit/miss, duplicate-fan-out, and
skew/collision shapes. Candidate losses remain in the receipt. Passing the flat
memory calculation without its speed and adversarial-shape gates does not
admit it, and none of these gates changes the default layout automatically.

The
[full R5g receipt](receipts/2026-07-26-r5g-index-layouts/summary.md) clears
every gate. `PackedSorted` retains 6.500 bytes/source row; the optimized
batch32 path reports 432.018 B/op for `FastHash` and 472.049 B/op for
`CompactSorted`; and `FlatHashRows` retains 10.667 bytes/source row while
beating compact by 2.720x for the unique single hit and 2.575x for the unique
batch. The flat layout is admitted only for low-fanout workloads: compact is
4.65x faster for the skewed single-key lookup and 2.48x faster for the skewed
batch. The default remains `FastHash`.
