# R5k.1 matched join endpoint decision

This court replaces the R5j dataframe parity claim. The R5j timings remain
valid measurements of frame4s' internal gathered-result construction, but
pandas and Polars returned eagerly usable dataframes. Those endpoints were not
equivalent.

R5k.1 measures three different operations:

- `gather-view` constructs frame4s' internal row-selection arrays and lazy
  gathered columns. It is a frame4s-only diagnostic.
- `deep-materialized` constructs an output with physical primitive column
  buffers. This is the dataframe construction comparator.
- `matched-consumption` constructs that result and sums all four output
  columns. This measures an identical downstream operation in each engine.

Input construction is outside every timed operation. frame4s preparation is
reported separately.

## Semantic contract

Every engine returns these required columns in this order:

1. `key: Int32`
2. `leftValue: Int64`
3. `rightKey: Int32`
4. `rightValue: Int64`

pandas joins `key` to `rightKey` with `sort=False`. Polars uses
`maintain_order="left"` and `coalesce=False`. frame4s retains its documented
stable-left order. Validation compares cardinality, schema, a SHA-256 digest of
the exact ordered output bytes, a four-column sum, and both input-key
fingerprints. All implementations match in every measured size and order.

## Measurement design

The court runs three process-level rounds. Backend order rotates between
rounds, so frame4s is measured first, fourth, and third. Results below are
medians across those processes. Ratios are paired within each round before
their median is taken. The minimum and maximum process medians remain in
`summary.md`; they are wide for some allocation-heavy 4M cases and must not be
hidden behind a single point estimate.

The host is an Apple M3 Max with 14 logical processors. The versions are
pandas 3.0.1, Polars 1.43.1, NumPy 2.5.1, Scala 3.7.4, and Java 25.0.1.
Polars is measured with one thread and its 14-thread default pool.

## Corrected construction standing

| Rows | Order | frame4s deep | pandas | Polars 1t | Polars 14t | f4s/pandas | f4s/Polars 1t | f4s/Polars 14t |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 1M | both shuffled | 112.266 ms | 46.475 ms | 79.022 ms | 25.089 ms | 2.43x | 1.69x | 5.73x |
| 1M | right shuffled | 159.781 ms | 35.918 ms | 71.758 ms | 54.363 ms | 3.81x | 1.42x | 2.94x |
| 1M | sorted | 119.920 ms | 2.253 ms | 52.173 ms | 13.997 ms | 42.62x | 1.82x | 8.21x |
| 4M | both shuffled | 778.983 ms | 289.000 ms | 489.206 ms | 131.473 ms | 1.73x | 1.74x | 6.77x |
| 4M | right shuffled | 587.693 ms | 277.906 ms | 379.802 ms | 132.119 ms | 1.91x | 1.66x | 5.76x |
| 4M | sorted | 454.436 ms | 8.214 ms | 285.326 ms | 76.856 ms | 47.37x | 1.40x | 6.28x |

Ratios above use paired process rounds, so they do not always equal the ratio
of the displayed cross-process medians.

The old frame4s gathered-view medians are 9.983 ms at 1M sorted and
42.799 ms at 4M sorted. Deep materialization therefore adds about 110 ms and
412 ms to the displayed medians. It also raises median allocation from
32.01 MB to 204.02 MB at 1M and from 128.01 MB to 816.03 MB at 4M. The useful
four-column payload is approximately 24 MB and 96 MB.

## Corrected consumption standing

The matched four-column reduction exposes the same boundary. At 1M sorted,
frame4s takes 255.810 ms, pandas 2.927 ms, pinned Polars 52.767 ms, and default
Polars 15.831 ms. At 4M sorted, the times are 773.633, 11.720, 303.309, and
72.228 ms.

This endpoint is not a checksum proxy. Each engine performs the same logical
sum after constructing the same four-column result. Exact order and content
are verified outside timing.

## Decision

The R5j gathered-view improvements remain admitted internal kernel
improvements. Their pandas and Polars ratios are withdrawn as dataframe
construction claims. R5k uses `deep-materialized` for construction ratios and
`matched-consumption` for downstream-use ratios.

The new evidence also changes the next implementation target. Sorted detection
and merge selection account for only part of the corrected gap. The current
generic conversion from gathered vectors to physical columns boxes scalar
values and allocates about 8.5 bytes for every useful output byte. R5k.3 must
combine its range-based sorted representation with typed physical
materialization; optimizing merge probing alone cannot close the measured
dataframe gap.

`summary.md` contains the complete table and process ranges.
`process-order.tsv` records backend rotation. `validation.tsv` contains the
cross-engine semantic proof. Each round retains JMH or `timeit` samples,
allocation, stage attribution, versions, hardware, and thread counts.
