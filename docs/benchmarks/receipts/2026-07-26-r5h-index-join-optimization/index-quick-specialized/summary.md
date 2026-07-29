# frame4s R5h extended secondary-index court

Quick wiring receipt; timings and gate decisions are provisional. All five layouts and all four query shapes passed exact stable-scan validation in the quick court. Candidate losses remain visible below.

## Decisions

- `PackedSorted` is **admitted as the lower-memory sorted layout**.
- The clone-once/token-reuse batch engine is **not admitted**.
- `FlatHashRows` is **admitted as an explicit low-fanout balanced layout; not as a skewed-key layout**.
- `GroupedHash` is **admitted as the duplicate-adaptive hash layout**.
- `FastHash` remains the default; this court does not infer an automatic
  layout policy.

## Precommitted gates

| Gate | Result | Threshold | Status |
|---|---:|---:|---:|
| Packed owned bytes/source row | 6.500 | <= 6.750 | pass |
| Packed reduction versus compact | 18.75% | >= 15.00% | pass |
| FastHash batch32 allocation | 475.728 B/op | <= 500.000 | pass |
| CompactSorted batch32 allocation | 480.509 B/op | <= 500.000 | pass |
| FastHash batch32 frozen-point ratio | 0.789x | <= 1.100x | pass |
| CompactSorted batch32 frozen-point ratio | 0.510x | <= 1.100x | pass |
| Flat owned bytes/source row | 10.667 | <= 12.000 | pass |
| Flat unique single speedup versus compact | 3.047x | >= 1.250x | pass |
| Flat unique batch32 speedup versus compact | 2.590x | >= 1.250x | pass |
| Grouped unique owned bytes/source row | 10.667 | <= 12.000 | pass |
| Grouped fanout-8 owned bytes/source row | 5.833 | <= 6.000 | pass |
| Grouped skew owned bytes/source row | 10.615 | reported | reported |
| Grouped fanout-8 reduction versus flat | 45.31% | >= 40.00% | pass |
| Grouped all-equal owned bytes/source row | not run | <= 4.100 | provisional |
| Grouped unique single speedup versus compact | 2.985x | >= 2.000x | pass |
| Grouped fanout-8 single speedup versus compact | 1.360x | >= 1.000x | pass |
| Grouped skew batch32 speedup versus flat | 1.871x | >= 1.500x | pass |
| Balanced FastHash fanout ratio versus R5g | 0.691x | <= 0.900x | pass |
| Balanced Compact fanout ratio versus R5g | 1.184x | <= 0.900x | fail |
| Dominant FastHash skew ratio versus R5g | 1.050x | <= 1.100x | pass |
| Dominant Compact skew ratio versus R5g | 0.874x | <= 1.100x | pass |
| Dominant Packed skew ratio versus R5g | 0.892x | <= 1.100x | pass |
| Dominant Flat skew ratio versus R5g | 0.929x | <= 1.100x | pass |
| Maximum skew batch32 allocation | 63098.029 B/op | <= 70000.000 B/op | pass |
| Flat skewed single ratio versus compact | 0.250x | reported loss/win | reported |
| Flat skewed batch32 ratio versus compact | 0.379x | reported loss/win | reported |

The frozen-point ratios are conservative cross-run guardrails. The adaptive
batch path selects heap merging for pointer-cheap balanced hash streams,
direct merging for ordered dominant streams, and sorting where traversal
cost makes either merge slower. Same-run layout
rankings remain primary, and all losses remain visible.

## Full shape matrix

| Benchmark | Input rows | Mode | Time or throughput | Allocation | Output rows | Checksum or owned bytes | Layout | Shape |
|---|---:|---:|---:|---:|---:|---:|---|---|
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 18.1905 ms/op | 20794704.889 B/op | 1000000 | `20777216` | fast-hash | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 17.6496 ms/op | 20794704.889 B/op | 1000000 | `20777216` | fast-hash | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 12.9777 ms/op | 20790523.333 B/op | 1000000 | `20777216` | fast-hash | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 17.6184 ms/op | 20794704.889 B/op | 1000000 | `20777216` | fast-hash | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 18.7233 ms/op | 16018552.889 B/op | 1000000 | `8000000` | compact-sorted | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 18.5734 ms/op | 16018552.889 B/op | 1000000 | `8000000` | compact-sorted | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 13.6817 ms/op | 16014366.667 B/op | 1000000 | `8000000` | compact-sorted | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 8.40446 ms/op | 16009742.737 B/op | 1000000 | `8000000` | compact-sorted | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 19.5521 ms/op | 18520721.000 B/op | 1000000 | `6500000` | packed-sorted | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 19.1471 ms/op | 18518627.556 B/op | 1000000 | `6500000` | packed-sorted | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 15.5959 ms/op | 18516952.800 B/op | 1000000 | `6500000` | packed-sorted | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 9.44165 ms/op | 18510749.647 B/op | 1000000 | `6500000` | packed-sorted | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 19.4073 ms/op | 10720572.000 B/op | 1000000 | `10666672` | flat-hash-rows | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 19.4099 ms/op | 10720572.000 B/op | 1000000 | `10666672` | flat-hash-rows | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 35.2084 ms/op | 10732795.200 B/op | 1000000 | `10666672` | flat-hash-rows | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 69.2662 ms/op | 10750434.667 B/op | 1000000 | `10666672` | flat-hash-rows | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 32.9867 ms/op | 26701144.000 B/op | 1000000 | `10666672` | grouped-hash | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 33.2146 ms/op | 26701144.000 B/op | 1000000 | `10666672` | grouped-hash | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 16.8031 ms/op | 21850278.400 B/op | 1000000 | `5833336` | grouped-hash | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 23.5427 ms/op | 26638001.143 B/op | 1000000 | `10614600` | grouped-hash | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000303797 ms/op | 475.728 B/op | 32 | `14554966086508252864` | fast-hash | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000192976 ms/op | 346.897 B/op | 16 | `7260442535237621654` | fast-hash | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00178547 ms/op | 2279.162 B/op | 256 | `11883708537156009700` | fast-hash | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.133157 ms/op | 63098.029 B/op | 7842 | `16398796646659458615` | fast-hash | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000776456 ms/op | 480.509 B/op | 32 | `14554966086508252864` | compact-sorted | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000590563 ms/op | 350.468 B/op | 16 | `7260442535237621654` | compact-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00341380 ms/op | 2271.127 B/op | 256 | `11883708537156009700` | compact-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0289072 ms/op | 62996.729 B/op | 7842 | `16398796646659458615` | compact-sorted | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000825811 ms/op | 481.293 B/op | 32 | `14554966086508252864` | packed-sorted | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000603794 ms/op | 350.620 B/op | 16 | `7260442535237621654` | packed-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00330155 ms/op | 2272.310 B/op | 256 | `11883708537156009700` | packed-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0341397 ms/op | 63002.739 B/op | 7842 | `16398796646659458615` | packed-sorted | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000299760 ms/op | 475.785 B/op | 32 | `14554966086508252864` | flat-hash-rows | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000296422 ms/op | 347.624 B/op | 16 | `7260442535237621654` | flat-hash-rows | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00331757 ms/op | 2269.994 B/op | 256 | `11883708537156009700` | flat-hash-rows | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0762014 ms/op | 63042.554 B/op | 7842 | `16398796646659458615` | flat-hash-rows | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000288642 ms/op | 475.529 B/op | 32 | `14554966086508252864` | grouped-hash | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000278763 ms/op | 347.786 B/op | 16 | `7260442535237621654` | grouped-hash | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00337327 ms/op | 2267.330 B/op | 256 | `11883708537156009700` | grouped-hash | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0407187 ms/op | 63007.752 B/op | 7842 | `16398796646659458615` | grouped-hash | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 6.02635e-06 ms/op | 1.290 B/op | 1 | `1000030` | fast-hash | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.53678e-06 ms/op | 0.604 B/op | 0 | `0` | fast-hash | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 4.23788e-05 ms/op | 101.168 B/op | 8 | `3188334135560712` | fast-hash | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.135144 ms/op | 62739.275 B/op | 7813 | `14591963101091370202` | fast-hash | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.61420e-05 ms/op | 1.957 B/op | 1 | `1000030` | compact-sorted | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.09057e-05 ms/op | 0.800 B/op | 0 | `0` | compact-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 4.13867e-05 ms/op | 100.920 B/op | 8 | `3188334135560712` | compact-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0194187 ms/op | 62627.357 B/op | 7813 | `14591963101091370202` | compact-sorted | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.59199e-05 ms/op | 1.931 B/op | 1 | `1000030` | packed-sorted | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.10484e-05 ms/op | 0.824 B/op | 0 | `0` | packed-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 4.58882e-05 ms/op | 100.874 B/op | 8 | `3188334135560712` | packed-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0283958 ms/op | 62636.486 B/op | 7813 | `14591963101091370202` | packed-sorted | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.29745e-06 ms/op | 0.889 B/op | 1 | `1000030` | flat-hash-rows | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.13550e-06 ms/op | 0.483 B/op | 0 | `0` | flat-hash-rows | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.79311e-05 ms/op | 122.367 B/op | 8 | `3188334135560712` | flat-hash-rows | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0776535 ms/op | 62683.921 B/op | 7813 | `14591963101091370202` | flat-hash-rows | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.40760e-06 ms/op | 1.051 B/op | 1 | `1000030` | grouped-hash | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.02851e-06 ms/op | 0.473 B/op | 0 | `0` | grouped-hash | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 3.04321e-05 ms/op | 136.945 B/op | 8 | `3188334135560712` | grouped-hash | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0323320 ms/op | 62639.595 B/op | 7813 | `14591963101091370202` | grouped-hash | skewed |

`owned bytes` are retained arrays returned by each build. JMH allocation
for `ExtendedIndexBuildCourt.build` includes temporary radix arrays and
packing. Raw JMH output is under `raw/`; `validation.tsv` records layout,
shape, output cardinality, and checksum. The frozen scan provenance is
`2026-07-26-r5e-index-baseline/summary.md`.
