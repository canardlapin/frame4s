# ADR 0003: explicit immutable secondary indices

Status: direct and compact layouts accepted; prepared join reuse remains
experimental

Date: 2026-07-26

Decision issue: `bd-01KYFVGR24KEH030VX9XB2DY24`

Layout follow-up issue: `bd-01KYG2RW0ZRDHEXECMA6ZCVV9N`

## Context

The admitted columnar candidate rebuilds its right-side integer hash table on
every join execution. The R5d data.table court also shows that a reusable
secondary index can improve repeated equality lookup at 100,000 to 1,000,000
rows, while losing on small or one-shot work once construction is counted.

An index must not make `Frame[S]` effectful, hide mutation in query
construction, outlive its source binding, or change stable filtering and join
semantics.

## Decision

R5e starts with one package-internal `Int32` secondary index and one explicit
prepared-execution entry point. It is an optimized-backend capability, not a
new public dataframe operation.

- Ordinary `ColumnarInterpreter.prepare` remains one-shot and does not cache.
- The indexed preparation path builds eagerly. Its cold receipt therefore
  includes construction, and warm execution is reported separately.
- An index is bound to the exact `ReferenceSources` value, `SourceRef`, schema,
  key-column position, and source row count from which it was built. A
  separately rebound source with the same schema cannot reuse it.
- The index owns only detached JVM/Scala.js arrays. It never owns or extends
  the source table's buffers. `close()` invalidates those arrays; use after
  close is a structured error.
- Stored row ordinals are stable source-order positions. Duplicate matches and
  multi-key lookup are emitted in source order, matching filter and hash-join
  order. Null keys are not indexed because SQL equality with null is unknown,
  not true.
- Initial key support is physical plain `Int32`. Unsupported encodings, types,
  shapes, or stale bindings remain explicit residuals.

The internal experiment must succeed before any public spelling is designed.
A future public capability would need a typed singleton column name, a
resource-scoped owner, and a backend capability receipt; callers would not
handle untyped column positions.

## Admission gates

Before implementation, R5e records exact lower-bound linear scans at 100,000
and 1,000,000 rows for a single key and a 32-key batch. The indexed candidate
is admitted only when:

1. output cardinality, values, checksums, duplicate order, null behavior,
   closed-state behavior, and binding checks pass on JVM and Scala.js;
2. warm 32-key lookup is at least 2x faster than the scan at 100,000 rows and
   at least 5x faster at 1,000,000 rows;
3. warm single-key lookup is at least 2x faster at 1,000,000 rows;
4. owned index arrays use at most 24 bytes per source row at 1,000,000 rows;
5. cold construction and calculated break-even query counts remain published;
   and
6. ordinary one-shot execution and the semantic interpreter remain unchanged.

Join reuse is admitted separately from direct lookup. It must beat the
one-shot hash-join path after including preparation, and must not retain
borrowed source buffers.

## Outcome

The direct index passed every precommitted gate in the
[full admission receipt](../benchmarks/receipts/2026-07-26-r5e-index-admitted/summary.md).
At 1,000,000 rows, warm lookup was 8,685x faster for one key and 16,497x
faster for 32 keys than the frozen scans. The index used 20.777 bytes per
source row; measured construction broke even after 120.61 single-key queries
or 3.13 32-key queries.

The separate
[prepared-join receipt](../benchmarks/receipts/2026-07-26-r5e-prepared-join/summary.md)
did not justify general join admission. Preparation plus one execution beat
the same-run one-shot path only for the sparse fixture (1.05x cold and 1.10x
warm). One-to-one, one-to-many, and skewed joins all regressed in the final
exact-source run. `prepareIndexed` therefore remains package-internal
experimental evidence. Ordinary one-shot preparation is still the admitted
default, and no automatic shape policy is inferred from four fixtures.

## R5f layout follow-up

R5f preserves the admitted hash index as the speed control and introduces an
explicit layout choice:

- `FastHash` keeps the separate key, head, and duplicate-chain arrays. Its
  single-key path probes and materializes one chain directly, without cloning,
  sorting, or deduplicating a one-element query.
- `CompactSorted` owns parallel sorted-key and source-row-ordinal arrays.
  Construction uses a stable signed-`Int32` radix sort, so duplicates retain
  source order. Lookup uses a lower bound and scans the equal-key range.

Both layouts have identical binding, ownership, null, duplicate, output-order,
and close semantics. The layout is an explicit build capability, not an
automatic cache or a property inferred from query history.

A denser open-addressed variant is rejected before implementation. At the
maximum 0.75 load factor, separate `Int32` key and head arrays plus one
duplicate-chain `Int32` per source row have a lower bound of 14.67 bytes per
row before object overhead. It therefore cannot clear R5f's 12-byte target.
Lowering that footprint requires changing representation, which is the
purpose of `CompactSorted`.

### R5f admission gates

The layout court re-executes the frozen R5e scan fixtures and checksums at
100,000 and 1,000,000 rows in the same runtime as both layouts. Before full
measurement, R5f requires:

1. both layouts to pass the R5e semantic laws plus differential scan checks,
   source-order and batch-partition metamorphisms, duplicates, nulls, empty
   inputs, `Int.MinValue`/`Int.MaxValue`, multi-batch sources, binding, and
   close behavior on JVM and Scala.js;
2. `CompactSorted` to use at most 12 owned bytes per source row at 1,000,000
   rows and at least 40% fewer owned bytes than `FastHash`;
3. `CompactSorted` warm lookup to remain at least 2x faster than the frozen
   1,000,000-row single-key scan and 5x faster than the 32-key scan;
4. the optimized `FastHash` single-key path to be at least 1.25x faster than
   the same-run former batch-compatible one-key path and use at most 72
   normalized allocated bytes per operation;
5. construction time, warm lookup, normalized allocation, owned bytes, and
   calculated break-even query counts to remain published; and
6. no change to ordinary one-shot execution, `Frame` purity, or the semantic
   interpreter.

Passing admits `CompactSorted` as an explicit memory-oriented layout. It does
not by itself justify changing the default or choosing a layout automatically.
The earlier R5e point estimate remains descriptive because it was captured on
a different JVM; it is not treated as a hard cross-runtime regression gate.

### R5f outcome

The
[full layout receipt](../benchmarks/receipts/2026-07-26-r5f-index-layouts/summary.md)
passes every precommitted gate. At 1,000,000 rows:

- `CompactSorted` owns 8,000,000 bytes, exactly 8 bytes per source row and
  61.50% less than `FastHash`;
- compact one-key lookup takes 0.000018208 ms/op, 8,761x faster than the
  same-runtime scan, and compact 32-key lookup takes 0.001521 ms/op, 3,952x
  faster;
- compact construction takes 22.024 ms and allocates 16.003 MB in total,
  versus 23.761 ms and 20.780 MB for `FastHash`; and
- the specialized `FastHash` one-key path takes 0.000008617 ms/op with
  effectively zero normalized allocation, 2.32x faster than the same-run
  former path at 0.000020013 ms/op and 96.001 B/op.

`CompactSorted` is therefore accepted as the explicit memory-oriented layout.
`FastHash` remains the default because its warm one-key and 32-key lookups are
about 2.11x and 3.95x faster than compact at 1,000,000 rows. No automatic
selection policy is inferred from this fixture.

## R5g packed and balanced-layout follow-up

R5g keeps both admitted R5f layouts as controls and evaluates two different
representations:

- `PackedSorted` keeps the sorted `Int32` key array but stores non-negative
  source-row ordinals in the minimum fixed bit width implied by source row
  count. At 1,000,000 rows each ordinal needs 20 bits, for 6.5 owned bytes per
  indexed source row when there are no nulls.
- `FlatHashRows` stores `(key, source-row-ordinal)` directly in
  open-addressed slots at no more than 0.75 occupancy. It has no head array and
  no dense duplicate-chain array. At 1,000,000 non-null rows its two arrays
  own 10,666,672 bytes, or 10.667 bytes per source row.

The packed store is continuous across `Int32` word boundaries. Its width is
derived from the source row count, not the number of indexed rows, because
ordinals address the source. Zero- and one-row sources need no ordinal words.
Construction still sorts ordinary `Int32` ordinals first and packs only after
the stable signed radix sort; the receipt must therefore distinguish retained
owned bytes from temporary build allocation.

The flat layout inserts source rows in ascending ordinal order. Linear probing
starts from the same mixed-key slot for every equal key. Consequently, all
slots for that key occur in insertion order within the probe cluster, including
when the cluster wraps. Lookup may stop at the first empty slot because no
later equal key can exist beyond an empty slot under this insertion rule.
Tests must force both distinct-key collisions and wrap-around rather than
checking only collision-free permutations.

The row slot also uses bit 30 to mark that another equal key follows. The
existing `MaxRows` invariant is strictly below `2^30`, so this tag cannot
collide with a valid ordinal; bit 31 remains clear for occupied slots and the
negative empty sentinel remains unambiguous. Unique lookup can therefore stop
without scanning the rest of its cluster, while duplicate lookup follows the
same probe order until the marked continuation is found. Slot selection uses
31-bit multiply-and-shift range reduction rather than integer division and
still maps every hash into `[0, capacity)`.

Batch lookup remains layout-neutral. It clones caller-owned keys once, sorts
and compacts the distinct prefix in that same buffer, replaces each distinct
key with its first backend token after the counting pass, and reuses that token
while materializing. This removes the second distinct-key array and the second
initial probe without mutating the caller's input. The final ordinal sort
continues to provide stable source order across different query keys.

### R5g admission gates

Before full measurement, R5g requires:

1. all four layouts to pass exact scan differential tests, caller-key
   immutability, source order, query permutation, batch partition, null/empty,
   signed-boundary, close, binding, lifetime-detachment, packed word-boundary,
   and flat collision/wrap laws on JVM and Scala.js;
2. `PackedSorted` to own at most 6.75 bytes per source row at 1,000,000
   non-null rows and at least 15% less than `CompactSorted`;
3. the optimized 32-key path for both `FastHash` and `CompactSorted` to
   allocate at most 500 normalized bytes per operation, with no more than a
   10% same-run latency regression from the frozen R5f point;
4. `FlatHashRows` to own at most 12 bytes per source row and to be at least
   1.25x faster than `CompactSorted` on both designated 1,000,000-row unique
   one-key and 32-key workloads before it is admitted as the balanced layout;
5. unique hits, mixed misses, duplicate fan-out, and skew/collision fixtures to
   publish exact checksums, warm latency, allocation, construction, and owned
   bytes, including losses; and
6. `FastHash` to remain the default unless the complete same-run court—not a
   memory calculation or a single favorable fixture—supports a change.

These are representation admission gates. They do not authorize automatic
index construction, implicit caching, or a production engine inside the
semantic interpreter.

### R5g outcome

The
[full extended receipt](../benchmarks/receipts/2026-07-26-r5g-index-layouts/summary.md)
passes every precommitted gate:

- `PackedSorted` owns 6,500,000 bytes at 1,000,000 rows, exactly 6.500
  bytes/source row and 18.75% less than `CompactSorted`;
- the clone-once/token-reuse batch path allocates 432.018 B/op for `FastHash`
  and 472.049 B/op for `CompactSorted`, below the 500 B/op ceiling and down
  from roughly 616 B/op in R5f;
- `FlatHashRows` owns 10.667 bytes/source row and is 2.720x faster than
  `CompactSorted` on the designated unique hit and 2.575x faster on the
  unique 32-key batch; and
- all four layouts agree exactly with stable scans on unique, mixed-miss,
  fan-out-8, and skewed fixtures.

The widened court also limits the conclusion. On the hot-key fixture, flat
lookup is only 0.215x the compact single-key speed and 0.404x its batch speed.
It is therefore admitted only as an explicit low-fanout balanced layout, not
as a skew-oriented choice. `PackedSorted` is the lower-memory sorted layout,
and `FastHash` remains the default.

The first quick flat prototype failed both unique speed gates because finding
the end of a cluster dominated unique lookup. The preserved quick receipt
records that failure. The admitted representation uses the spare continuation
bit described above. The first full version then exposed a 133.080 ms skewed
build. A 4,096-entry maximum build-only last-slot cache, sized down for small
tables and released before publication, reduces that result to 66.069 ms
without changing retained bytes. A dedicated 1,000,000-row all-equal build
takes 5.703 ms, guarding against quadratic repeated-key insertion. The
pre-cache full receipt remains under `pre-build-cache/`.

## Rejected alternatives

- Automatic indexing during `Frame` construction: violates purity and makes
  query cost history-dependent.
- A global or thread-local index cache: breaks explicit source binding and
  ownership.
- Comparing only warm lookup with a scan: hides construction and memory cost.
- Binding by source id and schema alone: a later table could reuse the same
  logical id and silently receive stale row ordinals.
- Adding index logic to `ReferenceInterpreter`: turns the semantic oracle into
  the production engine it is meant to check.
