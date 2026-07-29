# frame4s R5h extended secondary-index court

Quick wiring receipt; timings and gate decisions are provisional. All five layouts and all four query shapes passed exact stable-scan validation in the quick court. Candidate losses remain visible below.

## Decisions

- `PackedSorted` is **admitted as the lower-memory sorted layout**.
- The clone-once/token-reuse batch engine is **admitted as the adaptive batch lookup path**.
- `FlatHashRows` is **admitted as an explicit low-fanout balanced layout; not as a skewed-key layout**.
- `GroupedHash` is **admitted as the duplicate-adaptive hash layout**.
- `FastHash` remains the default; this court does not infer an automatic
  layout policy.

## Precommitted gates

| Gate | Result | Threshold | Status |
|---|---:|---:|---:|
| Packed owned bytes/source row | 6.500 | <= 6.750 | pass |
| Packed reduction versus compact | 18.75% | >= 15.00% | pass |
| FastHash batch32 allocation | 475.490 B/op | <= 500.000 | pass |
| CompactSorted batch32 allocation | 472.756 B/op | <= 500.000 | pass |
| FastHash batch32 frozen-point ratio | 0.742x | <= 1.100x | pass |
| CompactSorted batch32 frozen-point ratio | 0.490x | <= 1.100x | pass |
| Flat owned bytes/source row | 10.667 | <= 12.000 | pass |
| Flat unique single speedup versus compact | 2.825x | >= 1.250x | pass |
| Flat unique batch32 speedup versus compact | 2.432x | >= 1.250x | pass |
| Grouped unique owned bytes/source row | 10.667 | <= 12.000 | pass |
| Grouped fanout-8 owned bytes/source row | 5.833 | <= 6.000 | pass |
| Grouped skew owned bytes/source row | 10.615 | reported | reported |
| Grouped fanout-8 reduction versus flat | 45.31% | >= 40.00% | pass |
| Grouped all-equal owned bytes/source row | not run | <= 4.100 | provisional |
| Grouped unique single speedup versus compact | 2.857x | >= 2.000x | pass |
| Grouped fanout-8 single speedup versus compact | 6.468x | >= 1.000x | pass |
| Grouped skew batch32 speedup versus flat | 1.878x | >= 1.500x | pass |
| Balanced FastHash fanout ratio versus R5g | 0.654x | <= 0.900x | pass |
| Balanced Compact fanout ratio versus R5g | 0.804x | <= 0.900x | pass |
| Dominant FastHash skew ratio versus R5g | 1.029x | <= 1.100x | pass |
| Dominant Compact skew ratio versus R5g | 0.901x | <= 1.100x | pass |
| Dominant Packed skew ratio versus R5g | 0.913x | <= 1.100x | pass |
| Dominant Flat skew ratio versus R5g | 0.981x | <= 1.100x | pass |
| Maximum skew batch32 allocation | 63094.749 B/op | <= 70000.000 B/op | pass |
| Flat skewed single ratio versus compact | 0.701x | reported loss/win | reported |
| Flat skewed batch32 ratio versus compact | 0.370x | reported loss/win | reported |

The frozen-point ratios are conservative cross-run guardrails. The adaptive
batch path selects heap merging for pointer-cheap balanced hash streams,
direct merging for ordered dominant streams, and sorting where traversal
cost makes either merge slower. Same-run layout
rankings remain primary, and all losses remain visible.

## Full shape matrix

| Benchmark | Input rows | Mode | Time or throughput | Allocation | Output rows | Checksum or owned bytes | Layout | Shape |
|---|---:|---:|---:|---:|---:|---:|---|---|
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 92.0766 ms/op | 20853312.000 B/op | 1000000 | `20777216` | fast-hash | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 26.7328 ms/op | 20803077.333 B/op | 1000000 | `20777216` | fast-hash | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 18.2954 ms/op | 20794704.889 B/op | 1000000 | `20777216` | fast-hash | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 20.3413 ms/op | 20796798.000 B/op | 1000000 | `20777216` | fast-hash | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 21.6648 ms/op | 16020646.000 B/op | 1000000 | `8000000` | compact-sorted | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 18.8116 ms/op | 16018552.889 B/op | 1000000 | `8000000` | compact-sorted | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 15.6141 ms/op | 16016878.400 B/op | 1000000 | `8000000` | compact-sorted | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 10.3020 ms/op | 16011230.500 B/op | 1000000 | `8000000` | compact-sorted | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 19.9482 ms/op | 18520721.000 B/op | 1000000 | `6500000` | packed-sorted | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 20.4680 ms/op | 18520721.000 B/op | 1000000 | `6500000` | packed-sorted | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 15.5103 ms/op | 18516952.800 B/op | 1000000 | `6500000` | packed-sorted | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 9.26223 ms/op | 18510749.647 B/op | 1000000 | `6500000` | packed-sorted | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 24.4054 ms/op | 10723482.286 B/op | 1000000 | `10666672` | flat-hash-rows | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 25.3763 ms/op | 10723482.286 B/op | 1000000 | `10666672` | flat-hash-rows | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 37.3557 ms/op | 10732795.200 B/op | 1000000 | `10666672` | flat-hash-rows | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 74.3682 ms/op | 10750434.667 B/op | 1000000 | `10666672` | flat-hash-rows | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 33.1830 ms/op | 26701144.000 B/op | 1000000 | `10666672` | grouped-hash | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 34.1207 ms/op | 26701144.000 B/op | 1000000 | `10666672` | grouped-hash | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 17.4471 ms/op | 21851952.889 B/op | 1000000 | `5833336` | grouped-hash | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 25.8270 ms/op | 26638001.143 B/op | 1000000 | `10614600` | grouped-hash | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000285474 ms/op | 475.490 B/op | 32 | `14554966086508252864` | fast-hash | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000182098 ms/op | 346.688 B/op | 16 | `7260442535237621654` | fast-hash | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00169036 ms/op | 2278.392 B/op | 256 | `11883708537156009700` | fast-hash | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.130486 ms/op | 63094.749 B/op | 7842 | `16398796646659458615` | fast-hash | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000745116 ms/op | 472.756 B/op | 32 | `14554966086508252864` | compact-sorted | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000583297 ms/op | 350.553 B/op | 16 | `7260442535237621654` | compact-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00231848 ms/op | 2280.757 B/op | 256 | `11883708537156009700` | compact-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0298121 ms/op | 62997.714 B/op | 7842 | `16398796646659458615` | compact-sorted | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000777111 ms/op | 473.410 B/op | 32 | `14554966086508252864` | packed-sorted | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000599239 ms/op | 350.625 B/op | 16 | `7260442535237621654` | packed-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00330323 ms/op | 2267.647 B/op | 256 | `11883708537156009700` | packed-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0349184 ms/op | 63002.626 B/op | 7842 | `16398796646659458615` | packed-sorted | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000306435 ms/op | 475.747 B/op | 32 | `14554966086508252864` | flat-hash-rows | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000277623 ms/op | 347.399 B/op | 16 | `7260442535237621654` | flat-hash-rows | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00331035 ms/op | 2271.133 B/op | 256 | `11883708537156009700` | flat-hash-rows | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0804752 ms/op | 63046.945 B/op | 7842 | `16398796646659458615` | flat-hash-rows | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000322091 ms/op | 475.920 B/op | 32 | `14554966086508252864` | grouped-hash | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000303073 ms/op | 347.744 B/op | 16 | `7260442535237621654` | grouped-hash | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00314441 ms/op | 2274.652 B/op | 256 | `11883708537156009700` | grouped-hash | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0428620 ms/op | 63009.341 B/op | 7842 | `16398796646659458615` | grouped-hash | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.80781e-06 ms/op | 0.947 B/op | 1 | `1000030` | fast-hash | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.42595e-06 ms/op | 0.485 B/op | 0 | `0` | fast-hash | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 4.30552e-05 ms/op | 101.264 B/op | 8 | `3188334135560712` | fast-hash | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.133661 ms/op | 62741.131 B/op | 7813 | `14591963101091370202` | fast-hash | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.49631e-05 ms/op | 1.726 B/op | 1 | `1000030` | compact-sorted | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.07162e-05 ms/op | 0.780 B/op | 0 | `0` | compact-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.000160185 ms/op | 108.459 B/op | 8 | `3188334135560712` | compact-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0585132 ms/op | 62662.623 B/op | 7813 | `14591963101091370202` | compact-sorted | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.62444e-05 ms/op | 2.121 B/op | 1 | `1000030` | packed-sorted | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.20999e-05 ms/op | 0.903 B/op | 0 | `0` | packed-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 4.30343e-05 ms/op | 100.099 B/op | 8 | `3188334135560712` | packed-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0263363 ms/op | 62635.287 B/op | 7813 | `14591963101091370202` | packed-sorted | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.29711e-06 ms/op | 0.889 B/op | 1 | `1000030` | flat-hash-rows | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.39335e-06 ms/op | 0.421 B/op | 0 | `0` | flat-hash-rows | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.40854e-05 ms/op | 137.224 B/op | 8 | `3188334135560712` | flat-hash-rows | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0835031 ms/op | 62689.155 B/op | 7813 | `14591963101091370202` | flat-hash-rows | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.23811e-06 ms/op | 0.830 B/op | 1 | `1000030` | grouped-hash | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.41464e-06 ms/op | 0.652 B/op | 0 | `0` | grouped-hash | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 2.47673e-05 ms/op | 99.642 B/op | 8 | `3188334135560712` | grouped-hash | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0225140 ms/op | 62630.114 B/op | 7813 | `14591963101091370202` | grouped-hash | skewed |

`owned bytes` are retained arrays returned by each build. JMH allocation
for `ExtendedIndexBuildCourt.build` includes temporary radix arrays and
packing. Raw JMH output is under `raw/`; `validation.tsv` records layout,
shape, output cardinality, and checksum. The frozen scan provenance is
`2026-07-26-r5e-index-baseline/summary.md`.
