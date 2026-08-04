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
| FastHash batch32 allocation | 475.722 B/op | <= 500.000 | pass |
| CompactSorted batch32 allocation | 480.331 B/op | <= 500.000 | pass |
| FastHash batch32 frozen-point ratio | 0.783x | <= 1.100x | pass |
| CompactSorted batch32 frozen-point ratio | 0.484x | <= 1.100x | pass |
| Flat owned bytes/source row | 10.667 | <= 12.000 | pass |
| Flat unique single speedup versus compact | 2.827x | >= 1.250x | pass |
| Flat unique batch32 speedup versus compact | 2.395x | >= 1.250x | pass |
| Grouped unique owned bytes/source row | 10.667 | <= 12.000 | pass |
| Grouped fanout-8 owned bytes/source row | 5.833 | <= 6.000 | pass |
| Grouped skew owned bytes/source row | 10.615 | reported | reported |
| Grouped fanout-8 reduction versus flat | 45.31% | >= 40.00% | pass |
| Grouped all-equal owned bytes/source row | not run | <= 4.100 | provisional |
| Grouped unique single speedup versus compact | 2.754x | >= 2.000x | pass |
| Grouped fanout-8 single speedup versus compact | 1.319x | >= 1.000x | pass |
| Grouped skew batch32 speedup versus flat | 2.026x | >= 1.500x | pass |
| Balanced FastHash fanout ratio versus R5g | 0.643x | <= 0.900x | pass |
| Balanced Compact fanout ratio versus R5g | 0.776x | <= 0.900x | pass |
| Dominant FastHash skew ratio versus R5g | 1.000x | <= 1.100x | pass |
| Dominant Compact skew ratio versus R5g | 0.828x | <= 1.100x | pass |
| Dominant Packed skew ratio versus R5g | 0.804x | <= 1.100x | pass |
| Dominant Flat skew ratio versus R5g | 0.958x | <= 1.100x | pass |
| Maximum skew batch32 allocation | 63091.124 B/op | <= 70000.000 B/op | pass |
| Flat skewed single ratio versus compact | 0.237x | reported loss/win | reported |
| Flat skewed batch32 ratio versus compact | 0.349x | reported loss/win | reported |

The frozen-point ratios are conservative cross-run guardrails. The adaptive
batch path selects heap merging for pointer-cheap balanced hash streams,
direct merging for ordered dominant streams, and sorting where traversal
cost makes either merge slower. Same-run layout
rankings remain primary, and all losses remain visible.

## Full shape matrix

| Benchmark | Input rows | Mode | Time or throughput | Allocation | Output rows | Checksum or owned bytes | Layout | Shape |
|---|---:|---:|---:|---:|---:|---:|---|---|
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 16.8292 ms/op | 20793030.400 B/op | 1000000 | `20777216` | fast-hash | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 17.8238 ms/op | 20794704.889 B/op | 1000000 | `20777216` | fast-hash | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 12.3606 ms/op | 20789556.923 B/op | 1000000 | `20777216` | fast-hash | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 19.5274 ms/op | 20796798.000 B/op | 1000000 | `20777216` | fast-hash | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 18.3623 ms/op | 16018552.889 B/op | 1000000 | `8000000` | compact-sorted | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 17.3489 ms/op | 16018552.889 B/op | 1000000 | `8000000` | compact-sorted | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 14.1814 ms/op | 16015508.364 B/op | 1000000 | `8000000` | compact-sorted | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 8.01244 ms/op | 16009742.737 B/op | 1000000 | `8000000` | compact-sorted | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 19.9221 ms/op | 18520721.000 B/op | 1000000 | `6500000` | packed-sorted | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 24.5514 ms/op | 18523412.571 B/op | 1000000 | `6500000` | packed-sorted | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 16.3866 ms/op | 18516952.800 B/op | 1000000 | `6500000` | packed-sorted | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 9.88828 ms/op | 18511304.000 B/op | 1000000 | `6500000` | packed-sorted | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 27.5015 ms/op | 10727362.667 B/op | 1000000 | `10666672` | flat-hash-rows | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 19.7352 ms/op | 10720572.000 B/op | 1000000 | `10666672` | flat-hash-rows | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 36.2309 ms/op | 10732795.200 B/op | 1000000 | `10666672` | flat-hash-rows | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 87.1573 ms/op | 10775552.000 B/op | 1000000 | `10666672` | flat-hash-rows | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 32.5325 ms/op | 26701144.000 B/op | 1000000 | `10666672` | grouped-hash | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 32.7150 ms/op | 26701144.000 B/op | 1000000 | `10666672` | grouped-hash | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 17.5247 ms/op | 21851952.889 B/op | 1000000 | `5833336` | grouped-hash | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 24.3284 ms/op | 26638001.143 B/op | 1000000 | `10614600` | grouped-hash | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000301211 ms/op | 475.722 B/op | 32 | `14554966086508252864` | fast-hash | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000182368 ms/op | 346.692 B/op | 16 | `7260442535237621654` | fast-hash | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00166199 ms/op | 2277.994 B/op | 256 | `11883708537156009700` | fast-hash | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.126778 ms/op | 63091.124 B/op | 7842 | `16398796646659458615` | fast-hash | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000736114 ms/op | 480.331 B/op | 32 | `14554966086508252864` | compact-sorted | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000549109 ms/op | 350.017 B/op | 16 | `7260442535237621654` | compact-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00223864 ms/op | 2281.595 B/op | 256 | `11883708537156009700` | compact-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0273963 ms/op | 62995.515 B/op | 7842 | `16398796646659458615` | compact-sorted | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000779802 ms/op | 480.564 B/op | 32 | `14554966086508252864` | packed-sorted | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000604661 ms/op | 350.642 B/op | 16 | `7260442535237621654` | packed-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00320993 ms/op | 2273.897 B/op | 256 | `11883708537156009700` | packed-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0307659 ms/op | 62999.289 B/op | 7842 | `16398796646659458615` | packed-sorted | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000307356 ms/op | 475.758 B/op | 32 | `14554966086508252864` | flat-hash-rows | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000300717 ms/op | 347.789 B/op | 16 | `7260442535237621654` | flat-hash-rows | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00312879 ms/op | 2272.844 B/op | 256 | `11883708537156009700` | flat-hash-rows | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0785559 ms/op | 63046.301 B/op | 7842 | `16398796646659458615` | flat-hash-rows | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000308576 ms/op | 475.773 B/op | 32 | `14554966086508252864` | grouped-hash | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000277433 ms/op | 347.744 B/op | 16 | `7260442535237621654` | grouped-hash | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00291766 ms/op | 2275.787 B/op | 256 | `11883708537156009700` | grouped-hash | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0387702 ms/op | 63006.026 B/op | 7842 | `16398796646659458615` | grouped-hash | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.71676e-06 ms/op | 0.853 B/op | 1 | `1000030` | fast-hash | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.25481e-06 ms/op | 0.547 B/op | 0 | `0` | fast-hash | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 3.93126e-05 ms/op | 102.402 B/op | 8 | `3188334135560712` | fast-hash | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.127614 ms/op | 62732.343 B/op | 7813 | `14591963101091370202` | fast-hash | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.48674e-05 ms/op | 1.807 B/op | 1 | `1000030` | compact-sorted | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.08076e-05 ms/op | 0.742 B/op | 0 | `0` | compact-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 3.71114e-05 ms/op | 101.291 B/op | 8 | `3188334135560712` | compact-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0181892 ms/op | 62625.753 B/op | 7813 | `14591963101091370202` | compact-sorted | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.57735e-05 ms/op | 2.060 B/op | 1 | `1000030` | packed-sorted | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.12202e-05 ms/op | 0.804 B/op | 0 | `0` | packed-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 4.52562e-05 ms/op | 100.770 B/op | 8 | `3188334135560712` | packed-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0257652 ms/op | 62634.048 B/op | 7813 | `14591963101091370202` | packed-sorted | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.25903e-06 ms/op | 0.858 B/op | 1 | `1000030` | flat-hash-rows | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 4.92947e-06 ms/op | 0.494 B/op | 0 | `0` | flat-hash-rows | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.00467e-05 ms/op | 137.170 B/op | 8 | `3188334135560712` | flat-hash-rows | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0766241 ms/op | 62683.352 B/op | 7813 | `14591963101091370202` | flat-hash-rows | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.39835e-06 ms/op | 0.716 B/op | 1 | `1000030` | grouped-hash | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.01365e-06 ms/op | 0.418 B/op | 0 | `0` | grouped-hash | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 2.81381e-05 ms/op | 121.507 B/op | 8 | `3188334135560712` | grouped-hash | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0311922 ms/op | 62638.482 B/op | 7813 | `14591963101091370202` | grouped-hash | skewed |

`owned bytes` are retained arrays returned by each build. JMH allocation
for `ExtendedIndexBuildCourt.build` includes temporary radix arrays and
packing. Raw JMH output is under `raw/`; `validation.tsv` records layout,
shape, output cardinality, and checksum. The frozen scan provenance is
`2026-07-26-r5e-index-baseline/summary.md`.
