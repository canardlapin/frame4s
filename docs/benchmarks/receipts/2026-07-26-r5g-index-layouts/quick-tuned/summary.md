# frame4s R5g extended secondary-index court

Quick wiring receipt; timings and gate decisions are provisional. All four
layouts passed exact stable-scan validation on the 1,000,000-row unique
control. Candidate losses remain visible below.

## Decisions

- `PackedSorted` is **admitted as the lower-memory sorted layout**.
- The clone-once/token-reuse batch engine is **admitted as the batch lookup path**.
- `FlatHashRows` **provisionally clears the low-fanout balanced-layout gates**.
- `FastHash` remains the default; this court does not infer an automatic
  layout policy.

## Precommitted gates

| Gate | Result | Threshold | Status |
|---|---:|---:|---:|
| Packed owned bytes/source row | 6.500 | <= 6.750 | pass |
| Packed reduction versus compact | 18.75% | >= 15.00% | pass |
| FastHash batch32 allocation | 444.978 B/op | <= 500.000 | pass |
| CompactSorted batch32 allocation | 479.228 B/op | <= 500.000 | pass |
| FastHash batch32 frozen-point ratio | 0.703x | <= 1.100x | pass |
| CompactSorted batch32 frozen-point ratio | 0.518x | <= 1.100x | pass |
| Flat owned bytes/source row | 10.667 | <= 12.000 | pass |
| Flat unique single speedup versus compact | 2.769x | >= 1.250x | pass |
| Flat unique batch32 speedup versus compact | 2.811x | >= 1.250x | pass |

The two frozen-point ratios are conservative cross-run guardrails. Same-run
layout rankings come from this receipt; a host/JDK difference is not
presented as a causal code regression.

## Full shape matrix

| Benchmark | Input rows | Mode | Time or throughput | Allocation | Output rows | Checksum or owned bytes | Layout | Shape |
|---|---:|---:|---:|---:|---:|---:|---|---|
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 17.3828 ms/op | 20796068.444 B/op | 1000000 | `20777216` | fast-hash | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 19.4948 ms/op | 16020646.000 B/op | 1000000 | `8000000` | compact-sorted | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 19.1848 ms/op | 18518627.556 B/op | 1000000 | `6500000` | packed-sorted | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 18.1315 ms/op | 10685500.444 B/op | 1000000 | `10666672` | flat-hash-rows | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000270443 ms/op | 444.978 B/op | 32 | `14554966086508252864` | fast-hash | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000787489 ms/op | 479.228 B/op | 32 | `14554966086508252864` | compact-sorted | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000791806 ms/op | 479.724 B/op | 32 | `14554966086508252864` | packed-sorted | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000280142 ms/op | 475.246 B/op | 32 | `14554966086508252864` | flat-hash-rows | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.41882e-06 ms/op | 0.898 B/op | 1 | `1000030` | fast-hash | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.44320e-05 ms/op | 1.696 B/op | 1 | `1000030` | compact-sorted | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.57306e-05 ms/op | 1.838 B/op | 1 | `1000030` | packed-sorted | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.21183e-06 ms/op | 0.858 B/op | 1 | `1000030` | flat-hash-rows | unique |

`owned bytes` are retained arrays returned by each build. JMH allocation
for `ExtendedIndexBuildCourt.build` includes temporary radix arrays and
packing. Raw JMH output is under `raw/`; `validation.tsv` records layout,
shape, output cardinality, and checksum. The frozen scan provenance is
`baseline/summary.md`.
