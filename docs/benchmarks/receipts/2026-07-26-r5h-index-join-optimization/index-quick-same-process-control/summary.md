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
| FastHash batch32 allocation | 475.882 B/op | <= 500.000 | pass |
| CompactSorted batch32 allocation | 473.239 B/op | <= 500.000 | pass |
| FastHash batch32 frozen-point ratio | 0.826x | descriptive cross-run control | reported |
| CompactSorted batch32 frozen-point ratio | 0.502x | descriptive cross-run control | reported |
| Flat owned bytes/source row | 10.667 | <= 12.000 | pass |
| Flat unique single speedup versus compact | 2.868x | >= 1.250x | pass |
| Flat unique batch32 speedup versus compact | 2.604x | >= 1.250x | pass |
| Grouped unique owned bytes/source row | 10.667 | <= 12.000 | pass |
| Grouped fanout-8 owned bytes/source row | 5.833 | <= 6.000 | pass |
| Grouped skew owned bytes/source row | 10.615 | reported | reported |
| Grouped fanout-8 reduction versus flat | 45.31% | >= 40.00% | pass |
| Grouped all-equal owned bytes/source row | not run | <= 4.100 | provisional |
| Grouped unique single speedup versus compact | 2.943x | >= 2.000x | pass |
| Grouped fanout-8 single speedup versus compact | 1.838x | >= 1.000x | pass |
| Grouped skew batch32 speedup versus flat | 2.077x | >= 1.500x | pass |
| Balanced FastHash adaptive/sort-control ratio | 0.631x | <= 0.900x | pass |
| Balanced Compact adaptive/sort-control ratio | 0.708x | <= 0.900x | pass |
| Dominant FastHash adaptive/sort-control ratio | 0.891x | <= 1.100x | pass |
| Dominant Compact adaptive/sort-control ratio | 0.801x | <= 1.100x | pass |
| Dominant Packed adaptive/sort-control ratio | 0.861x | <= 1.100x | pass |
| Dominant Flat adaptive/sort-control ratio | 0.874x | <= 1.100x | pass |
| Maximum skew batch32 allocation | 63092.036 B/op | <= 70000.000 B/op | pass |
| Maximum skew sort-control allocation | 95018.770 B/op | same-run control | reported |
| FastHash fanout ratio versus R5g | 0.678x | descriptive cross-run control | reported |
| Compact fanout ratio versus R5g | 0.745x | descriptive cross-run control | reported |
| FastHash skew ratio versus R5g | 1.004x | descriptive cross-run control | reported |
| Compact skew ratio versus R5g | 0.860x | descriptive cross-run control | reported |
| Packed skew ratio versus R5g | 0.877x | descriptive cross-run control | reported |
| Flat skew ratio versus R5g | 0.945x | descriptive cross-run control | reported |
| Flat skewed single ratio versus compact | 0.233x | reported loss/win | reported |
| Flat skewed batch32 ratio versus compact | 0.367x | reported loss/win | reported |

The gate comparator is the exact primitive-sort strategy with the same
backend, fixture, counting pass, JVM, and process. Frozen R5g points remain
visible as descriptive provenance, not host-dependent admission gates. The
adaptive path selects heap merging for pointer-cheap balanced hash streams,
direct merging for ordered dominant streams, and sorting where traversal
cost makes either merge slower.

## Full shape matrix

| Benchmark | Input rows | Mode | Time or throughput | Allocation | Output rows | Checksum or owned bytes | Layout | Shape |
|---|---:|---:|---:|---:|---:|---:|---|---|
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 17.6276 ms/op | 20794704.889 B/op | 1000000 | `20777216` | fast-hash | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 17.9814 ms/op | 20794704.889 B/op | 1000000 | `20777216` | fast-hash | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 12.2486 ms/op | 20789556.923 B/op | 1000000 | `20777216` | fast-hash | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 16.6774 ms/op | 20793030.400 B/op | 1000000 | `20777216` | fast-hash | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 18.6479 ms/op | 16018552.889 B/op | 1000000 | `8000000` | compact-sorted | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 18.4960 ms/op | 16018552.889 B/op | 1000000 | `8000000` | compact-sorted | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 14.6664 ms/op | 16015508.364 B/op | 1000000 | `8000000` | compact-sorted | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 8.62483 ms/op | 16009742.737 B/op | 1000000 | `8000000` | compact-sorted | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 19.1708 ms/op | 18518627.556 B/op | 1000000 | `6500000` | packed-sorted | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 20.1894 ms/op | 18520721.000 B/op | 1000000 | `6500000` | packed-sorted | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 15.8580 ms/op | 18516952.800 B/op | 1000000 | `6500000` | packed-sorted | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 9.87018 ms/op | 18511304.000 B/op | 1000000 | `6500000` | packed-sorted | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 18.5048 ms/op | 10718308.444 B/op | 1000000 | `10666672` | flat-hash-rows | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 18.8076 ms/op | 10718308.444 B/op | 1000000 | `10666672` | flat-hash-rows | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 35.9201 ms/op | 10732795.200 B/op | 1000000 | `10666672` | flat-hash-rows | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 70.0288 ms/op | 10750434.667 B/op | 1000000 | `10666672` | flat-hash-rows | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 37.7691 ms/op | 26701144.000 B/op | 1000000 | `10666672` | grouped-hash | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 32.6657 ms/op | 26701153.600 B/op | 1000000 | `10666672` | grouped-hash | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 17.4176 ms/op | 21851952.889 B/op | 1000000 | `5833336` | grouped-hash | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 26.2304 ms/op | 26641589.333 B/op | 1000000 | `10614600` | grouped-hash | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000317729 ms/op | 475.882 B/op | 32 | `14554966086508252864` | fast-hash | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000212520 ms/op | 346.867 B/op | 16 | `7260442535237621654` | fast-hash | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00175266 ms/op | 2279.054 B/op | 256 | `11883708537156009700` | fast-hash | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.127303 ms/op | 63092.036 B/op | 7842 | `16398796646659458615` | fast-hash | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000763727 ms/op | 473.239 B/op | 32 | `14554966086508252864` | compact-sorted | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000562147 ms/op | 350.286 B/op | 16 | `7260442535237621654` | compact-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00214692 ms/op | 2282.079 B/op | 256 | `11883708537156009700` | compact-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0284480 ms/op | 62995.781 B/op | 7842 | `16398796646659458615` | compact-sorted | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000785840 ms/op | 473.567 B/op | 32 | `14554966086508252864` | packed-sorted | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000602869 ms/op | 350.661 B/op | 16 | `7260442535237621654` | packed-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00329606 ms/op | 2270.536 B/op | 256 | `11883708537156009700` | packed-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0335585 ms/op | 63001.963 B/op | 7842 | `16398796646659458615` | packed-sorted | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000293291 ms/op | 475.601 B/op | 32 | `14554966086508252864` | flat-hash-rows | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000283402 ms/op | 347.497 B/op | 16 | `7260442535237621654` | flat-hash-rows | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00342897 ms/op | 2291.333 B/op | 256 | `11883708537156009700` | flat-hash-rows | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0774822 ms/op | 63043.357 B/op | 7842 | `16398796646659458615` | flat-hash-rows | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000313027 ms/op | 475.827 B/op | 32 | `14554966086508252864` | grouped-hash | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000285755 ms/op | 347.497 B/op | 16 | `7260442535237621654` | grouped-hash | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00291798 ms/op | 2275.578 B/op | 256 | `11883708537156009700` | grouped-hash | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0373089 ms/op | 63004.415 B/op | 7842 | `16398796646659458615` | grouped-hash | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.64101e-06 ms/op | 0.950 B/op | 1 | `1000030` | fast-hash | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.27273e-06 ms/op | 0.502 B/op | 0 | `0` | fast-hash | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 4.01976e-05 ms/op | 102.557 B/op | 8 | `3188334135560712` | fast-hash | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.125241 ms/op | 62730.126 B/op | 7813 | `14591963101091370202` | fast-hash | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.50796e-05 ms/op | 1.776 B/op | 1 | `1000030` | compact-sorted | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.09231e-05 ms/op | 0.814 B/op | 0 | `0` | compact-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 4.07836e-05 ms/op | 100.863 B/op | 8 | `3188334135560712` | compact-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0177342 ms/op | 62625.811 B/op | 7813 | `14591963101091370202` | compact-sorted | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.61129e-05 ms/op | 1.826 B/op | 1 | `1000030` | packed-sorted | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.06882e-05 ms/op | 0.829 B/op | 0 | `0` | packed-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 4.60547e-05 ms/op | 100.543 B/op | 8 | `3188334135560712` | packed-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0269344 ms/op | 62635.101 B/op | 7813 | `14591963101091370202` | packed-sorted | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.25787e-06 ms/op | 0.933 B/op | 1 | `1000030` | flat-hash-rows | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 4.88857e-06 ms/op | 0.495 B/op | 0 | `0` | flat-hash-rows | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.67503e-05 ms/op | 122.202 B/op | 8 | `3188334135560712` | flat-hash-rows | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0761397 ms/op | 62681.983 B/op | 7813 | `14591963101091370202` | flat-hash-rows | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.12402e-06 ms/op | 0.869 B/op | 1 | `1000030` | grouped-hash | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 4.90621e-06 ms/op | 0.479 B/op | 0 | `0` | grouped-hash | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 2.21886e-05 ms/op | 100.074 B/op | 8 | `3188334135560712` | grouped-hash | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0234084 ms/op | 62630.876 B/op | 7813 | `14591963101091370202` | grouped-hash | skewed |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.000303689 ms/op | 475.722 B/op | 32 | `14554966086508252864` | fast-hash | unique |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.000206000 ms/op | 315.266 B/op | 16 | `7260442535237621654` | fast-hash | mixed-miss |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.00277902 ms/op | 2245.543 B/op | 256 | `11883708537156009700` | fast-hash | fanout-8 |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.142928 ms/op | 95018.770 B/op | 7842 | `16398796646659458615` | fast-hash | skewed |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.000804441 ms/op | 479.816 B/op | 32 | `14554966086508252864` | compact-sorted | unique |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.000561880 ms/op | 350.331 B/op | 16 | `7260442535237621654` | compact-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.00303060 ms/op | 2269.665 B/op | 256 | `11883708537156009700` | compact-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.0355232 ms/op | 94914.656 B/op | 7842 | `16398796646659458615` | compact-sorted | skewed |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.000823628 ms/op | 473.309 B/op | 32 | `14554966086508252864` | packed-sorted | unique |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.000588494 ms/op | 350.447 B/op | 16 | `7260442535237621654` | packed-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.00314041 ms/op | 2273.486 B/op | 256 | `11883708537156009700` | packed-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.0389731 ms/op | 94919.160 B/op | 7842 | `16398796646659458615` | packed-sorted | skewed |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.000321653 ms/op | 475.932 B/op | 32 | `14554966086508252864` | flat-hash-rows | unique |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.000285698 ms/op | 347.520 B/op | 16 | `7260442535237621654` | flat-hash-rows | mixed-miss |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.00319142 ms/op | 2269.915 B/op | 256 | `11883708537156009700` | flat-hash-rows | fanout-8 |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.0886541 ms/op | 94966.413 B/op | 7842 | `16398796646659458615` | flat-hash-rows | skewed |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.000304037 ms/op | 475.717 B/op | 32 | `14554966086508252864` | grouped-hash | unique |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.000289407 ms/op | 347.588 B/op | 16 | `7260442535237621654` | grouped-hash | mixed-miss |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.00284272 ms/op | 2270.604 B/op | 256 | `11883708537156009700` | grouped-hash | fanout-8 |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.0467796 ms/op | 94926.978 B/op | 7842 | `16398796646659458615` | grouped-hash | skewed |

`owned bytes` are retained arrays returned by each build. JMH allocation
for `ExtendedIndexBuildCourt.build` includes temporary radix arrays and
packing. Raw JMH output is under `raw/`; `validation.tsv` records layout,
shape, output cardinality, and checksum. The frozen scan provenance is
`2026-07-26-r5e-index-baseline/summary.md`.
