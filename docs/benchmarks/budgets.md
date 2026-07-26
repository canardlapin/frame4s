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
