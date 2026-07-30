# Performance parity plan

Goal: frame4s executes common local-dataframe pipelines faster than pandas and
within reach of Polars, on the path a user can actually call.

Ratified 2026-07-29. This plan does not change any existing receipt, fixture, or
threshold. It adds scale tiers and a new comparator, and it records one
deliberate policy change (promoting the columnar engine to the public runtime)
through an ADR.

## Starting position

Two findings motivate the plan.

**The public runtime does not execute the optimized engine.** `FrameRuntime` has
two execution entry points, `FrameRuntime.scala:282` and `:388`, and both call
`ReferenceInterpreter`. `ColumnarInterpreter` has no reference outside tests and
JMH. Every comparative number in `docs/benchmarks/receipts/` describes the
internal candidate; a caller of the public API gets the semantic reference
interpreter, which spends 39.5 ms and 237 MB/op on a 1,000-row one-to-one join.

**The court measures a regime where the comparators are pure overhead.** Every
committed fixture is 1,000 rows, except the dplyr practical court at 10,000. At
1,000 rows pandas is dominated by Python dispatch, which is why the receipts
report 21–66x wins. At 1,000,000 rows the picture reverses.

Scouting measurement, Apple M3 Max, JDK 22, single-threaded frame4s candidate,
pandas 3.0.5, Polars 1.43.1, 3 warmup and 5 measurement iterations. Not a
receipt. Milliseconds per operation:

| Workload at 1,000,000 rows | frame4s candidate | pandas, 1 thread | Polars, 1 thread | Polars, 14 threads |
|---|---:|---:|---:|---:|
| fused filter/project/materialize | 6.12 | 3.78 | 0.157 | 0.224 |
| grouped low cardinality, four statistics | 10.62 | 24.21 | 22.59 | 4.89 |
| grouped high cardinality, two statistics | 53.53 | 14.87 | 37.56 | 8.69 |
| one-to-one join | 100.93 | 1.91 | 42.38 | 8.63 |

Allocation at 1,000,000 rows: fused pipeline 16 MB/op, grouped high cardinality
113 MB/op, one-to-one join 111 MB/op.

Three caveats travel with that table, and all three are fixture weaknesses that
flatter the comparators rather than frame4s. pandas reaches a monotonic-key fast
path on the court's `arange` join fixture, so its join column is not a
general-case result. The primitive projection row is omitted entirely because
Polars answers it with a refcount clone while frame4s materializes owned output;
that shape is not comparable until the court states which contract it is
ranking, and `unionAll` needs the same scrutiny. The fused pipeline filters
`id >= rows / 2` on a monotonic `id`, so the selected rows are a contiguous
suffix and any engine with a slice or run-detection fast path wins it without
doing the general work; a scattered-selection fixture is needed before that ratio
is treated as a kernel comparison.

The candidate is therefore slower than single-threaded pandas on three of five
shapes at 1,000,000 rows, and 2–28x behind default Polars on four of five.

## Root causes

1. **Per-row interpretation inside the kernels.** ~~Per-element byte decode.~~
   The original suspect was the `Array[Byte]` representation, which decodes
   every `Int` with four masked loads and shifts. `DecodeBenchmarks` refutes
   that: at 1,000,000 elements the shift decode costs 583 us against 557 us for
   a native `Array[Int]`, a 4.6% penalty, because HotSpot already merges the
   loads. The `Double` penalty is 37%. A `VarHandle` is actively harmful here --
   Scala 3 does not intrinsify the signature-polymorphic call, so it boxes and
   runs 14x slower than the code it would replace. The representation is not the
   problem and must not be rewritten on this theory.

   The real cost is that kernels interpret per row. `FusedInt32.evaluateBatch`
   was 40.9% of runnable time, and for every element it paid a virtual `keep`
   dispatch that re-matched the comparison operator, a `Vector` trie lookup to
   reach the projection, a runtime type test to separate `Direct` from `Add`,
   and an `Option` check in both loop conditions -- none of which depends on the
   row.
2. **No parallelism.** `modules/core` contains no `Thread`, `ForkJoin`,
   `Executor`, or `.par`. Polars gains 4.3–4.9x from threads on grouping and
   joins on this host, so single-threaded execution alone caps frame4s about 5x
   below default Polars regardless of kernel quality.
3. **Transient allocation.** 111–113 MB/op on grouping and joins is roughly 113
   bytes of garbage per input row. `FixedWidthArray.withBorrowedValueBytes`
   (`Storage.scala:351`) copies the entire validity bitmap on every borrow.
4. **Row-major megamorphic consumption inside the timed region.**
   `ColumnarResult.checksum` (`ColumnarInterpreter.scala:145-162`) walks
   multi-column output row-major, dispatching a virtual `unsafeScalarHash` per
   cell into a serial `hash * 31` chain. The court partly measures its own
   validator.
5. **Copy-on-projection.** `Buffer` already supports refcounted `retain` and
   `slice`, but projection materializes new owned output. Polars shares. This is
   a contract question the court must state before it ranks the shape.

## Phases

Each phase ends with a committed dated receipt. Existing receipts, fixtures, and
thresholds are preserved untouched; new scale tiers get their own thresholds.

### Phase 0 — court infrastructure

Add a Polars comparator and large-scale tiers so every later change is judged
against the real bar rather than the 1,000-row regime.

- `scripts/polars-court.py` and `scripts/polars-court.sh`, following the pandas
  court's contract: oracle validation from a frame4s `validation.tsv`, exact
  checksums wherever floating reduction order permits, `raw/timings.csv`,
  `environment.properties`, and a receipt `summary.md`.
- Measure Polars both pinned to one thread and at its default thread count.
  The single-thread column is the apples-to-apples kernel comparison; the
  default column is the bar a user actually experiences.
- Add 1,000,000 and 10,000,000 row tiers to the JMH fixtures. Keep the 1,000-row
  tier and its ratified budgets exactly as they stand, and label them as
  overhead characterization rather than deleting them.
- State the projection contract explicitly. Either rank a materializing Polars
  shape or mark the zero-copy shape as an unranked lower bound, in the same way
  the court already handles Saddle's raw primitive scan.

### Phase 1 — column-at-a-time kernels

Hoist every per-element dispatch out of the row loops. Plan structure moves into
primitive arrays once per batch, the comparison operator is matched once and
branches into specialized scans, and each output column gets its own monomorphic
pass over a selection vector. Checked arithmetic stops branching per element: a
checked `x + literal` overflows for some selected row exactly when it overflows
at the minimum or maximum selected input, so the loop tracks those branch-free
and tests the bound once, with a cold rescan to reproduce the exact row-major
error. No public API change.

Done for `FusedInt32`: 1.92x faster at 1,000,000 rows (6124 to 3191 us), 1.89x
at 10,000 (63.2 to 33.4 us), and 25% less allocation (16.0 to 12.0 MB/op), with
all 173 core and testkit tests and small-tier oracle parity unchanged. The same
shape applies to the remaining kernels.

Remaining allocation is dominated by the selection vector, one `Int` per input
row per batch, which can be reused across batches rather than reallocated.

### Phase 2 — allocation, grouping, and joins

Stop copying the validity bitmap per borrow. Replace grouping and join hash
structures with reusable open-addressed primitive tables. Use arena-style
reusable output buffers. Fix `ColumnarResult.checksum` so validation stops
inflating every timed region, and re-run the affected receipts.

### Phase 3 — parallel execution

Decided: a `Scheduler` abstraction in `frame4s-core`, with a frame4s-owned
`ForkJoinPool` on the JVM and a sequential implementation on Scala.js. A
dedicated pool rather than `commonPool` so a neighbouring library's parallel
stream cannot starve query execution; work stealing because the court's skewed
fixtures are deliberately lopsided. Virtual threads and `StructuredTaskScope`
were rejected as the wrong tool -- they address blocking I/O, and CPU-bound work
multiplexed onto a carrier pool gains nothing. `frame4s-fs2` can inject a
cats-effect-backed `Scheduler` later so those users unify pools.

**Determinism is the binding constraint, not the pool.** Parallel float
reduction reassociates additions and would change every ratified checksum.
Partitioning by key rather than by row keeps every row of a group on one worker
in input order, so results stay bit-identical; output order is recovered by
recording each group's first-seen input ordinal and merging partitions on it.
This was verified end to end at 200,000 rows against a pre-parallelism receipt:
bit-identical, floating aggregates included.

**First attempt rejected on measurement.** Giving each of P workers a share of
the key space and having it scan every row to find its own divides the
accumulate work by P but multiplies the scan by P. At 1,000,000 rows and a
million groups: 124.3 ms with 16 partitions, 53.9 ms with 4, against 45.1 ms
sequential. A loss at every count, because scanning is not the cheap part once
the hash table stops fitting in cache. Reverted; only the `Scheduler` remains.

**Second attempt also rejected, and it is the more informative failure.** The
counting-sort partition pass was built, so each row is visited exactly once per
phase and the scan is divided rather than duplicated. It measured 122.4 ms
against the first attempt's 124.3 ms: essentially no change. High-cardinality
grouping at 1,000,000 rows, against 45.1 ms sequential:

| Partitions | 2 | 4 | 16 |
|---|---:|---:|---:|
| duplicated scan | 50.2 ms | 53.9 ms | 124.3 ms |
| single scan | 50.2 ms | -- | 122.4 ms |

Time rises monotonically with worker count. A million-group build is bound by
random access latency into a table far larger than cache, not by compute, so
adding workers adds concurrent random-access streams to the subsystem that is
already the bottleneck. No partition count and no cheaper partition pass rescues
this shape.

**The redirection paid off: 1.70x on the fused streaming kernel.** Two further
findings were needed, both only visible by measuring. Batch-level parallelism
was a no-op, because a frame4s table is frequently a single `RecordBatch`
holding every row, so splitting across batches handed one worker everything;
work is now split into row chunks inside a batch. And the kernel was copying its
own input, since `decodeBatch` called `ColumnarVector.copy` for a read-only
scan. At 1,000,000 rows over ten iterations: 2027 +/- 97 us against 3442 us
sequential, with allocation down from 12.0 to 8.0 MB/op. Chunk outputs are
concatenated in order, so the row sequence and checksum are unchanged --
verified bit-identical at both the 200,000-row scale tier and the ratified
1,000-row tier.

The conclusion is a redirection, not a smaller version of the same plan.
Parallelism should go first to kernels that stream rather than chase pointers --
filter, projection, arithmetic -- where access is sequential and bandwidth scales
with cores. Grouping and joins need a more compact, cache-resident table before
threading them is worth attempting again. Note that Polars' own single-threaded
grouping is 37.6 ms against our 45.1, so its 4.3x threading win comes from a
table design we do not have, not from threading a table like ours.

Note also that the two low-cardinality grouping workloads key on `Utf8`, not
`Int32`, so they run a different accumulator and were never touched by this
attempt.

### Phase 3 notes (original)

Morsel-parallel scan, grouped aggregation, and join build/probe. `frame4s-core`
must stay dependency-free and cross-built, so parallelism belongs behind a
platform-specific scheduler abstraction with a sequential Scala.js
implementation. Thread-pool ownership relative to the cats-effect runtime in
`frame4s-fs2` is a design decision recorded in its own ADR.

### Phase 4 — promote the columnar engine to the public runtime

Route `FrameRuntime` through `ColumnarInterpreter` with automatic fallback to
`ReferenceInterpreter` on capability residuals. The promotion goes through the
reusable conformance boundary in `frame4s-testkit`, surfaces any fallback in the
execution receipt rather than hiding it, and ships with an ADR recording the
rationale and a dated receipt measuring the public path. This runs last so the
engine being promoted is the improved one.

## Standing, at 1,000,000 rows

Single-threaded frame4s candidate, milliseconds per operation, against the
starting position recorded above. Scouting measurements, not receipts.

| Workload | Start | Now | Change |
|---|---:|---:|---:|
| fused filter/project | 6.12 | 2.03 | 3.02x |
| grouped high cardinality | 53.5 | 45.1 | 1.19x |
| one-to-one join | 100.9 | 89.1 | 1.13x |
| grouped low cardinality | 10.62 | 10.56 | unchanged |

Allocation on high-cardinality grouping moved 113.4 to 106.3 MB/op, and on the
fused pipeline 16.0 to 12.0 MB/op.

Phases 1 and 2 have reached diminishing returns in their current form. The
kernels no longer interpret per row, the court no longer substantially times its
own validator, and the remaining single-threaded gap is spread thinly rather
than concentrated in one hot spot. The largest untouched lever is Phase 3:
Polars gains 4.3--4.9x from threads on grouping and joins on this host, and no
amount of single-threaded kernel work closes that.

## Measured position against Polars, 1,000,000 rows

From the committed
[scale receipt](../benchmarks/receipts/2026-07-30-scale-1m/summary.md) and the
[Polars receipts](../benchmarks/receipts/2026-07-30-polars-1m/default/summary.md).
Ratio is frame4s over Polars, so below 1.00 means frame4s is faster.

| Ranked workload | vs Polars, 1 thread | vs Polars, 14 threads |
|---|---:|---:|
| grouped low cardinality, four statistics | **0.43x** | 2.15x |
| distinct low cardinality | **0.77x** | 3.40x |
| grouped low cardinality, sum only | 1.29x | 4.86x |
| semi join sparse | 1.68x | 4.55x |
| one-to-one join | 1.94x | 10.80x |
| sparse join | 2.02x | 6.00x |
| anti join sparse | 2.19x | 5.27x |
| one-to-many join | 2.32x | 10.76x |
| skewed join | 2.62x | 6.35x |
| fused filter/project | 15.97x | 12.30x |

Two workloads beat single-threaded Polars. None beats default-threaded Polars,
which is the bar a user actually meets. The gap is widest exactly where the
earlier phases already showed it: joins, and the fused pipeline whose fixture
selects a contiguous suffix and so rewards a slice fast path.

An open discrepancy travels with this: the fused pipeline measures 3.165 ms in
the full-court receipt and 2.03 ms in isolation, at identical allocation, so it
is the same code path. Warmup does not explain it, since isolated runs at the
court's own three-iteration warmup also produced roughly 2 ms, and JMH forks
each benchmark separately so cross-benchmark JIT pollution is ruled out. What
remains is the court's 8 GiB pre-touched heap and its second timing mode. The
receipt number stands until that is understood, because it is what the committed
harness produces.

## Claim discipline

No comparative claim is published from the 1,000-row tier. A "faster than
pandas" claim requires the 1,000,000-row tier, exact checksums where floating
reduction order permits, and a stated thread count on both sides. A "approaching
Polars" claim additionally requires reporting the default-threaded Polars column,
not only the pinned single-thread one.
