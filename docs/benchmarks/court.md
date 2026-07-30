# The frame4s measurement court

The benchmark court is a non-published JVM project. It exists to falsify
performance claims, not to make the semantic reference interpreter look fast.
The semantic baseline is
[2026-07-26-r2-reference](receipts/2026-07-26-r2-reference/summary.md). The
current full optimized-kernel court and its admission decision are
[2026-07-26-r5-columnar-admitted-final](receipts/2026-07-26-r5-columnar-admitted-final/admission.md).
The exact-work follow-up is the
[R5c Saddle comparison](receipts/2026-07-26-r5c-saddle-exact/comparison.md);
the separate cross-runtime results are in the
[R5c Pandas receipt](receipts/2026-07-26-r5c-pandas/summary.md) and the
[R5d data.table receipt](receipts/2026-07-26-r5d-datatable/summary.md). The
explicit-index follow-up is split into the
[R5e direct-index admission](receipts/2026-07-26-r5e-index-admitted/summary.md)
and the
[R5e prepared-join experiment](receipts/2026-07-26-r5e-prepared-join/summary.md).
The accepted speed/memory layout follow-up is the
[R5f layout court](receipts/2026-07-26-r5f-index-layouts/summary.md).

The court uses sbt-jmh 0.4.8 and JMH 1.37. Fixture construction, query
construction, validation, and teardown occur outside timed methods. Each
receipt contains:

- raw JMH JSON and console output;
- average-time and throughput results;
- `gc.alloc.rate.norm` allocation measurements;
- untimed output-row and checksum validation for every workload;
- the JDK, JVM flags, heap, dependency versions, hardware description,
  row count, backend, and fallback status.

Run a short harness check with:

```sh
scripts/benchmark-court.sh docs/benchmarks/receipts/local-quick quick
```

Run the full court on a quiet host with:

```sh
scripts/benchmark-court.sh docs/benchmarks/receipts/YYYY-MM-DD-host full
```

Set `FRAME4S_BENCHMARK_ROWS` to change the committed 1,000-row fixture size.
Changing the size creates a new receipt; it does not replace or silently
relabel an existing one.

## Workload contract

The committed court covers:

- primitive and nullable scan;
- direct UTF-8 and dictionary scan;
- filter and filter/project with arithmetic;
- low- and high-cardinality count/sum/mean/variance aggregation;
- one-to-one, one-to-many, sparse, and skewed joins;
- CSV decoding;
- owned-table construction; and
- bounded scalar decoding.

Union, distinct, and semi/anti joins are present with reference and internal
columnar-candidate workloads. An optimized backend enters only through the
reusable conformance boundary in `frame4s.testkit`; a capability gap is an
explicit residual, never a hidden reference fallback.

The specialized-array methods are lower bounds that clarify representation
overhead. Saddle 4.0.0-M14 participates only in comparable JVM shapes. The
materialized primitive projection, fused filter/project, and nullable grouped
sum produce the same output row counts and checksums as frame4s. The raw
primitive scan and scalar sum-only grouped reduction are retained as
lower-bound context; they do less work and are not ranked as equivalent
comparators. Saddle has no claimed comparator for the SQL duplicate-key join
cases, frame4s ownership, dictionary layout, or CSV acquisition.

Pandas is measured in a separate single-process Python court because JMH
cannot provide a shared-process timing environment across the JVM and CPython.
The court consumes the Scala validation receipt, requires exact output rows and
checksums wherever floating reduction order permits, and can attach the
frame4s JMH measurements for an explicitly cross-runtime ratio:

```sh
scripts/pandas-court.sh \
  --receipt docs/benchmarks/receipts/YYYY-MM-DD-pandas \
  --rows 1000 \
  --oracle-validation docs/benchmarks/receipts/YYYY-MM-DD-jvm/validation.tsv \
  --frame4s-jmh docs/benchmarks/receipts/YYYY-MM-DD-jvm/raw/jmh.json
```

The full four-statistic group workload is shape-validated rather than
checksum-ranked because the two runtimes use different legal floating-point
reduction orders. All other admitted Pandas comparisons require exact
checksums.

dplyr also has a separate-process practical-pipeline court. Its two workloads
follow the verb composition in the
[official dplyr introduction](https://dplyr.tidyverse.org/articles/dplyr.html)
instead of timing isolated operators:

- two UTF-8 predicates, two immutable `withColumn` derivations, and a final
  projection, with `Option[Double]` propagation through missing measures; and
- a four-column projection followed by two nullable grouping keys and means
  over two nullable measure columns. The dplyr side uses the tutorial's
  `na.rm = TRUE`; frame4s uses its documented null-ignoring aggregate semantics.

The Scala queries use typed `filter`, `withColumn`, `select`, `groupBy`, and
`aggregate`; they do not reproduce dplyr's non-standard evaluation API.
Fixture and query construction occur outside timing. Both dplyr outputs must
match the semantic reference interpreter's row count and exact ordered
non-floating/null structure before the separate R and JMH measurements are
compared. Per-floating-column count, sum, sum of squares, minimum, maximum, and
row-weighted first and second moments must also agree within a declared `1e-10`
relative tolerance. The row-weighted terms bind floating values to output
order instead of accepting only the same marginal distribution. The frame4s
reference and candidate still require exact raw-bit checksums:

```sh
scripts/dplyr-practical-court.sh \
  docs/benchmarks/receipts/YYYY-MM-DD-dplyr-practical \
  full
```

The committed
[frame4s receipt](receipts/2026-07-29-dplyr-practical/frame4s/summary.md) and
[dplyr comparison](receipts/2026-07-29-dplyr-practical/dplyr/summary.md)
record the full 10,000-row court. The internal columnar candidate completes
the filter/derived-column/projection pipeline in 0.487 ms/op versus dplyr's
1.340 ms median, and the projected nullable grouping pipeline in 0.722 ms/op
versus 1.594 ms. These separate-process ratios are descriptive, not a claim
that the semantic reference runtime has been replaced.

The candidate rows forbid fallback. The current general expression compiler
covers Boolean composition, UTF-8 comparisons, and checked or floating
arithmetic over Int32, Int64, Float32, and Float64 columns. Direct projections
retain all storage types. General grouped reduction accepts projected nullable
Int32 and UTF-8 key vectors and multiple Float64 measures. Other physical
families remain explicit capability residuals rather than silently ranked
reference executions.

data.table is measured in a separate single-thread R process with the same
relational fixture and Scala validation receipt. Its ordinary relational
measurements disable automatic and reusable indices so repeated samples do not
silently exclude setup. The separate index study uses unsorted tables and
reports a forced linear scan, cold `setindexv` construction plus lookup, and
warm reuse at multiple scales:

```sh
scripts/datatable-court.sh \
  --receipt docs/benchmarks/receipts/YYYY-MM-DD-datatable \
  --rows 1000 \
  --oracle-validation docs/benchmarks/receipts/YYYY-MM-DD-jvm/validation.tsv \
  --frame4s-jmh docs/benchmarks/receipts/YYYY-MM-DD-jvm/raw/jmh.json \
  --index-scales 1000,100000,1000000
```

Warm indexed results are a capability and amortization study, not equivalent
frame4s win/loss rows. The court reports secondary-index bytes and the number
of repeated queries needed to recover the measured construction cost.

frame4s measures the corresponding explicit immutable index with:

```sh
scripts/index-court.sh \
  docs/benchmarks/receipts/YYYY-MM-DD-index \
  full \
  admitted \
  docs/benchmarks/receipts/YYYY-MM-DD-index-baseline
```

The index court uses the same unsorted permutation, single target, 32-target
batch, stable output order, and checksums as the data.table study. Its direct
lookup candidate is admitted. Prepared join reuse is measured separately with
`scripts/prepared-join-court.sh`; it remains experimental because it does not
beat the one-shot join across every designated shape after construction.

Run the side-by-side layout court against a same-runtime execution of the
frozen scan fixture with:

```sh
scripts/index-court.sh \
  docs/benchmarks/receipts/YYYY-MM-DD-index-layouts/baseline \
  full \
  baseline
scripts/index-court.sh \
  docs/benchmarks/receipts/YYYY-MM-DD-index-layouts \
  full \
  layouts \
  docs/benchmarks/receipts/YYYY-MM-DD-index-layouts/baseline
```

`FastHash` is the speed default. `CompactSorted` is the explicit
memory-oriented layout: the current full receipt records 8 bytes/source row
and stable scan parity without introducing an automatic cache or policy.

R5g extends that layout court with explicit `PackedSorted` and
`FlatHashRows` candidates plus a lower-allocation batch engine. The original
stable unique permutation remains the frozen control. The extended court also
uses mixed misses, duplicate fan-out, and skew/collision fixtures, with
fixture construction and validation outside timed methods. Every layout is
checked against a stable scan before timing, and the batch query array is
verified unchanged. Run it with:

```sh
scripts/index-court.sh \
  docs/benchmarks/receipts/YYYY-MM-DD-index-r5g \
  full \
  extended \
  docs/benchmarks/receipts/YYYY-MM-DD-index-r5g/baseline
```

The packed result reports retained words separately from temporary radix-sort
allocation. The flat result is called balanced only if it clears both the
12-byte ceiling and the two unique-workload speed gates against
`CompactSorted`; collision and duplicate losses are not hidden by the unique
fixture. `FastHash` remains the default in the absence of whole-court evidence
for changing it.

The
[full R5g receipt](receipts/2026-07-26-r5g-index-layouts/summary.md) admits
packed storage, the lower-allocation batch engine, and flat hashing only for
low-fanout workloads. It preserves the initially failing quick flat prototype
and the pre-build-cache full run. A dedicated all-equal build case checks that
the transient last-slot cache prevents quadratic repeated-key insertion; the
cache is released before the index is returned and excluded from retained
owned bytes.

Scautable is intentionally absent from relational rankings. Its fixed-resource
macro path and its runtime typed path are discussed separately in
[ingestion and onboarding](ingestion-onboarding.md).

## Scale tier

Every court above uses a 1,000-row fixture, or 10,000 for the dplyr practical
court. That is the regime where pandas and Polars are dominated by their own
per-call dispatch overhead, so a ratio measured there describes interpreter
startup rather than kernel quality. It is honest as overhead characterization
and worthless as a comparative claim.

The scale tier adds large fixtures without touching any ratified receipt,
fixture, or threshold:

```sh
scripts/scale-court.sh docs/benchmarks/receipts/YYYY-MM-DD-scale-1m full 1000000
```

The scale tier measures the columnar candidate alone. It has no reference
oracle, and that is a stated limit rather than an omission: the semantic
reference join at `ReferenceInterpreter.scala` is a full nested-loop cross
product, so a 1,000,000-row join is on the order of 10^12 predicate
evaluations. Candidate agreement with the reference interpreter is established
by the cross-platform conformance laws and by the small tier, whose receipts
remain the ratified oracle record. Scale-tier checksums are candidate
self-consistency values that detect drift between runs of that tier; they are
not independent proof of semantic correctness, and the receipt says so.

Saddle and the specialized-array lower bounds do not run at this tier, so a
scale receipt ranks nothing against them.

## Polars court

Polars is the strongest local-dataframe comparator, so its court is built to be
hard to win rather than easy:

```sh
scripts/polars-court.sh \
  --receipt docs/benchmarks/receipts/YYYY-MM-DD-polars/pinned \
  --threads 1 \
  --rows 1000000 \
  --oracle-validation docs/benchmarks/receipts/YYYY-MM-DD-scale-1m/validation.tsv \
  --frame4s-jmh docs/benchmarks/receipts/YYYY-MM-DD-scale-1m/raw/jmh.json
```

Three rules govern it.

Thread count is part of every result. Polars is measured pinned to one thread
and at its default pool. The pinned column is the kernel comparison against
single-threaded frame4s; the default column is the bar a user actually
experiences. A ratio published without a stated thread count is not a claim.

Zero-copy shapes are not ranked. A Polars projection is a refcount clone while
frame4s materializes owned output, so `primitiveMaterializedProjection` is
recorded as an unranked lower bound with a written reason, in the same way the
court already treats Saddle's raw primitive scan. `unionAll` deserves the same
scrutiny for the same reason.

Validation scales with the fixture. At or below 100,000 rows the court requires
exact raw-bit checksums. Above that a Python row walk costs more than the
measurement, so it requires row count, schema, and vectorized per-column
invariants -- count, null count, sum, sum of squares, minimum, maximum, and a
row-weighted term that binds values to output order -- mirroring the dplyr
practical court rather than quietly dropping validation at scale. Polars does
not maintain group or join order, so unordered shapes are invariant-validated
by construction.

Each receipt records whether its frame4s oracle row came from the semantic
reference interpreter or from the candidate-only scale tier, so a weaker
validation can never be mistaken for the oracle-backed one.

## The join fixture flatters pandas, not Polars

The relational fixtures build join keys with `arange` on both sides, so both
inputs are monotonically increasing. Measured at 1,000,000 rows, one thread,
with the same key multiset and output cardinality and only the ordering changed:

| Key order | pandas | Polars |
|---|---:|---:|
| sorted, as the committed fixture | 1.946 ms | 49.573 ms |
| left sorted, right shuffled | 32.278 ms | 48.620 ms |
| both shuffled | 40.093 ms | 50.361 ms |

pandas is 20.6x faster on the sorted case, so it is reaching an order-sensitive
fast path rather than executing the same join as the shuffled cases. Any
published frame4s-versus-pandas ratio on this fixture compares a hash join
against that fast path and should not be read as a hash-join comparison. Polars
is flat across all three, so its comparator number is not flattered by ordering.
The
[size-by-order regime court](receipts/2026-07-30-r5j-join-regimes/admission.md)
now measures frame4s under the same shuffled keys from 1K through 4M rows.

This also constrains what frame4s may do about it. Detecting sorted keys and
switching to a merge join is a legitimate technique, but adding it against this
fixture would be indistinguishable from fitting the benchmark. A scattered-key
join fixture has to exist first, so the optimization can be shown to generalize.

## Join construction and consumption are separate measurements

The original join benchmark returned `ColumnarResult.checksum`, so it timed a
serial walk over every output cell after constructing the detached result.
Polars' eager join timing stops at its result frame and has no corresponding
consumption pass. The join court now carries both paths:

- `*ExecutionOnly` constructs and blackholes the detached result, then closes it;
- the original method additionally computes the exact checksum.

Comparator ratios use the execution-only row. The checksum row remains in the
same receipt as a validation and consumption-cost measurement. At 1,000,000
one-to-one rows, the admitted candidate measures 29.90 ms execution-only and
68.12 ms with checksum. The difference is 56% of the consumed timing, so the
older 91.51/47.14 frame4s-to-Polars ratio combined unlike endpoints.

Fresh execution-matched receipts put one-to-one frame4s at 0.62x
single-threaded Polars (29.90 versus 48.08 ms) and 3.26x default 14-thread
Polars (29.90 versus 9.18 ms). Across all six joins, frame4s wins one-to-one and
one-to-many against pinned Polars; sparse, skewed, semi, and anti remain
1.23--1.46x slower. It remains 3.26--4.18x slower than default-threaded Polars.

The [R5i join receipt](receipts/2026-07-30-r5i-join-performance/admission.md)
also records the optimization court. Lazy duplicate chains, single-batch
right-row addressing, capacity-aware selection arrays, and transferring those
arrays into the result reduce one-to-one allocation from 110.73 to 65.17 MB/op.
All six measured join shapes improve, exact checksums remain unchanged, and the
1,000-row consumed path improves from the earlier 0.105 ms receipt to 0.036 ms.

A bounded parallel probe was measured and rejected. It reduced one-to-one
execution from 42.28 to 28.32 ms against the isolated allocation-only candidate,
but raised allocation from 65.17 to 97.07 MB/op. Its probe stage is demonstrably
parallelizable; its per-chunk selection ownership is not yet allocation-safe.
The sequential allocation reduction is admitted independently.

## Join behavior changes with size

The
[R5j regime receipt](receipts/2026-07-30-r5j-join-regimes/admission.md)
uses identical deterministic key permutations for frame4s, pandas, and Polars
at 1K, 4K, 16K, 64K, 256K, 1M, and 4M rows. It records sorted keys, a shuffled
right side, and both sides shuffled. Comparator validation happens outside the
timed operation.

At 1M rows frame4s takes 29.33--30.12 ms across the three orders, or
0.56--0.59x single-threaded Polars. At 4M it takes 0.52--0.69x
single-threaded Polars. The one-to-one single-thread gap is therefore closed at
the large tiers. The remaining gap is to default 14-thread Polars: 2.84--3.22x
at 1M and 2.98--3.25x at 4M.

pandas remains algorithm-sensitive. At 1M it takes 2.08 ms sorted, 29.48 ms
with the right side shuffled, and 44.67 ms with both sides shuffled. frame4s is
14.32x slower than sorted pandas, within 2% of its right-shuffled result, and
faster than its both-shuffled result. At 4M frame4s is 22.51x slower than
sorted pandas but 0.76--0.80x its shuffled times.

frame4s allocation stays near 65 bytes per output row across sizes and orders.
At 1M, build takes 13.60--14.69 ms and probe takes 14.26--15.12 ms. This makes
allocation-safe parallel probing the direct one-to-one lever. A sorted merge
kernel is still legitimate, but it remains a separate conditional optimization
whose win must hold at adjacent sizes without slowing the shuffled fixtures.

## The residual single-thread gap is shape-specific

The
[R5j.3 single-thread receipt](receipts/2026-07-30-r5j-single-thread/admission.md)
measures a narrower hash index and selection representation against the
immediately preceding commit. With the same JMH command and 1 GiB heap, all six
join shapes improve at both 1K and 1M. At 1M the median improvements are
1.24--1.69x; allocation falls by 8--25% on five shapes and is unchanged on the
skewed shape.

The corresponding Polars court covers sparse, semi, anti, and skewed joins at
256K, 1M, and 4M under both sorted and both-shuffled keys. Seventeen of 24
regimes meet the 1.05x pinned-thread target. Anti passes every regime. Sparse
passes at 256K and 4M but is 1.12--1.22x Polars at 1M. Semi changes winner with
size and order and misses only by 1.06x and 1.11x. Skew is the persistent
large-row residual, reaching 1.13--1.40x Polars in three regimes.

There is therefore no general 1M-row regime switch. The remaining boundary is
non-monotonic for sparse and semi joins and sustained only for large skewed
joins. Those seven misses remain visible as the lower bound for the next
single-thread work; they are not averaged into a parity claim.

## Sorted merge is a separate conditional win

After the shuffled controls existed, the
[R5j.4 merge receipt](receipts/2026-07-30-r5j-sorted-merge/admission.md)
admitted monotonic Int32 detection and stable merge selection. The detection
scan is timed. Inputs below 16K rows, key streams containing a null, and any key
inversion use the unchanged hash path.

Against the immediately preceding commit, sorted one-to-one construction is
2.26x faster at 256K, 2.91x at 1M, and 4.34x at 4M. Sorted allocation falls by
about one third. Neither shuffled control regresses, and the 1K tier is
unchanged. Exact JVM and Scala.js tests cover unique, duplicate, sparse,
nullable, empty, and multi-batch inputs.

This result must stay labelled as sorted merge performance. It does not revise
the shuffled hash-join ratios or make the sorted pandas comparison a hash-join
comparison.

## Claim discipline

No comparative claim is published from the 1,000-row tier. A "faster than
pandas" claim requires the scale tier, exact checksums or declared invariants,
and a stated thread count on both sides. An "approaching Polars" claim
additionally requires reporting the default-threaded Polars column, not only the
pinned one. The full rationale and the current standing are in
[the performance parity plan](../plans/2026-07-29-performance-parity-plan.md).

## Law court

`frame4s-testkit` is cross-built for JVM and Scala.js. Its generators
deliberately include invalid and wide schemas, optional and boundary numeric
values, Unicode, timestamps, dictionary encoding, legal batch splits,
duplicate/null/skewed join keys, checked expressions, and hostile CSV chunk
boundaries. ScalaCheck reports the replay seed and shrunk arguments for every
failure; domain shrinkers remove optional values and reduce numeric witnesses
without erasing the failing boundary.

The reusable backend boundary compares detached schema, rows, raw floating
bits, exact structured failures, and declared ordering. Unsupported
capabilities return a named residual with a backend receipt. The reference
backend runs through the same boundary, so an optimized backend does not earn
special access to interpreter internals.

## Receipt policy

Every designated workload is published, including losses. The semantic
reference path is the current best frame4s path and therefore the first R5
comparison baseline. After an optimized stage is admitted, the best admitted
frame4s result becomes the next baseline; later stages are never compared only
with the reference interpreter.

Performance thresholds are ratified in [budgets.md](budgets.md). A threshold,
fixture, or designated architectural-win workload may change only in a new
dated receipt that preserves the old result and states the reason before the
new optimization is judged.
