# R5k.4 chunk-parallel gather materialization

This receipt admits chunk-parallel physical gather on top of the
[R5k.3 typed gather](../2026-08-01-r5k3-typed-gather/admission.md). Output
rows are split into contiguous chunks aligned to 64 rows, so any two
chunks write disjoint validity and Bool bytes; each chunk runs the same
monomorphic copy loop through a cursor positioned by `cursorAt`, workers
write disjoint slices of the same fresh owned buffers, and per-chunk null
counts and failures are combined after the scheduler joins. Utf8 keeps
its two-pass shape: a parallel measuring pass, a sequential prefix over
per-chunk byte totals, and a parallel offset-and-copy pass into disjoint
byte ranges. The result is byte-identical to the sequential pass by
construction — no reassociation, no ordering recovery — and the
sequential path is taken whenever a single chunk covers the output, which
includes everything below the 65,536-row parallelism floor and all of
Scala.js.

## Measurement protocol

Two full courts ran on the identical candidate. The initial sample showed
scatter both directions on cells the diff cannot touch — gather-view
construction swung 0.45x to 1.87x across tiers — and one formal
small-tier gate failure at `4000/interleaved-gaps/gather-view`, a cell
that measured 28 us in the R5k.3 court and 66 us in that run while its
sibling endpoints improved. Per the R5k.2 protocol a fresh confirmation
court was run rather than waiving the limit. The confirmation passes all
nine precommitted gates and is the receipt of record in `main/` and
`scale/`; the initial sample's metrics are retained in `initial-sample/`
so the scatter is visible rather than discarded.

| Gate | Observed | Limit |
|---|---:|---:|
| semantic identity (all 69 cells) | 0 mismatches | 0 |
| small-tier latency | 0.7463x | ≤1.05x |
| large shuffled latency | 0.6289x | ≤1.03x |
| fallback allocation | 1.0008x | ≤1.05x |
| 1M aligned deep speedup vs R5k.2 | 8.0724x | ≥1.50x |
| 1M physical-output allocation | 1.0219x payload | ≤1.10x |
| 4M aligned deep speedup vs R5k.2 | 8.0448x | ≥1.50x |
| 4M physical-output allocation | 1.0211x payload | ≤1.10x |
| 4M run-selection allocation | 0.7500x | ≤0.80x |

## Result against the sequential typed gather

`comparison-vs-r5k3.tsv` holds every cell against the R5k.3 receipt.
Deep materialization, milliseconds, R5k.3 → confirmation:

| Rows | Workload | Deep materialized | Ratio |
|---:|---|---|---:|
| 1M | aligned unique | 45.88 → 17.45 | 0.38x |
| 1M | duplicate groups | 103.37 → 34.91 | 0.34x |
| 1M | multi-batch sorted | 51.87 → 20.10 | 0.39x |
| 1M | fully shuffled | 93.42 → 57.45 | 0.61x |
| 4M | aligned unique | 242.40 → 81.38 | 0.34x |
| 4M | multi-batch sorted | 257.76 → 72.56 | 0.28x |
| 4M | fully shuffled | 476.50 → 306.82 | 0.64x |
| 4M | late inversion | 339.74 → 165.83 | 0.49x |

The precommitted R5k.4 acceptance — sorted and shuffled deep
construction at least 1.5x faster at both 1M and 4M — is met: 2.63x and
2.98x sorted, 1.63x and 1.55x shuffled. Matched consumption improves on
all 29 large cells (0.30x–0.96x), and allocation is within 0.1% of
sequential everywhere, because the parallel pass allocates nothing per
chunk beyond the task array.

## Documented boundary

`256000/nullable-key/deep-materialized` regresses reproducibly: 1.51x in
the initial sample, 1.25x (9.63 → 12.05 ms) in the confirmation. It is a
random-access hash-selection gather at a tier where the working set is
close to cache-resident, so added workers add concurrent random-access
streams — the same mechanism the grouping attempts documented. It is not
tuned away by raising the parallelism floor because that would sacrifice
the larger reproduced 256K wins (aligned 0.37x, duplicate groups 0.39x,
offset 0.47x, multi-batch 0.46x). Recorded as a boundary; a
selection-aware floor (parallelize random-access gathers only above 512K)
is the candidate fix if the cell matters later.

All 151 core tests pass on JVM and Scala.js, including a 70,000-row
five-column identity test that exercises the parallel path end to end,
and 30 testkit tests. `source-files.sha256` binds the receipt to the
measured interpreter, scheduler, fixtures, runner, and scripts.
