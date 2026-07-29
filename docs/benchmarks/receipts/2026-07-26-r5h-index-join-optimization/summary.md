# R5h index and join optimization

R5h evaluates three related changes:

- `GroupedHash`, an explicit duplicate-adaptive secondary-index layout;
- adaptive multi-key lookup using balanced heap merge, ordered dominant merge,
  or primitive sort; and
- selection-gather join output backed by shared primitive row selections.

`FastHash` remains the default index layout. Ordinary query construction and
ordinary execution do not build or cache an index. The semantic reference
interpreter is unchanged.

## Evidence status

Early tuning runs exposed merge-policy regressions and remain in the sibling
quick-receipt directories. The final exact-source full index court validates
every layout and query shape, admits the adaptive batch path, and rejects
`GroupedHash` because its fan-out-8 single lookup misses the precommitted
no-loss gate. The full join court admits selection-gather materialization and
prepared reuse for the designated shapes.

## Results

| Result | Evidence |
|---|---|
| All five layouts and all four query shapes match stable scans | [full index court](index-full/summary.md) |
| Grouped retained memory is 10.667 B/row unique, 5.833 at fan-out 8, 10.615 on sparse skew, and 4.000 all-equal | [full tuning court](index-full/summary.md) |
| Balanced FastHash and compact fan-out latency is 0.626x and 0.757x R5g | [full index court](index-full/summary.md) |
| Dominant-skew allocation is at most 62,977.227 B/op, below the 70,000 B/op gate | [full index court](index-full/summary.md) |
| `GroupedHash` fan-out-8 single lookup is 0.759x compact speed, so the layout is rejected | [full index court](index-full/summary.md) |
| One-shot join allocation falls 45.99% to 70.97% | [join court](join-full/summary.md) |
| Warm prepared joins are 1.34x to 1.90x faster than improved one-shot joins | [join court](join-full/summary.md) |
| Construction plus first prepared execution is 1.18x to 1.73x faster on all four shapes | [join court](join-full/summary.md) |
| Current one-shot joins are 2.72x to 12.13x faster than Pandas | [Pandas court](pandas-full/summary.md) |
| Current one-shot joins are 6.22x to 26.48x faster than data.table | [data.table court](datatable-full/summary.md) |

Saddle has no semantically equivalent duplicate-key join in the current court,
so this receipt makes no Saddle join claim. The existing exact Saddle
comparisons remain projection, fused filter/project, and grouped sum.

## Soundness boundary

The index remains bound to one exact `ReferenceSources`, `SourceRef`, schema,
and key position. Null keys are excluded, duplicate rows remain in stable
source order, caller-owned query keys are cloned, and close invalidates owned
arrays. Bit 30 tags grouped overflow entries under the existing `< 2^30` row
bound; bit 31 remains the negative empty sentinel.

Join results own detached copied vectors. Selection-gather columns share
primitive batch and row arrays, and left-outer misses use a validity bitmap.
The cross-platform laws close source tables and executions before reading
joined results and check Boolean, Int32, Int64, Float32, Float64, UTF-8,
timestamp, null, and NaN behavior.

Quick and superseded tuning runs remain in the sibling subdirectories. They
show why the final batch policy specializes `FastHash` and `CompactSorted`
rather than applying one merge implementation to every token layout.
