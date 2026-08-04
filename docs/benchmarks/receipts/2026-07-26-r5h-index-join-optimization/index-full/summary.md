# frame4s R5h extended secondary-index court

Full R5h grouped-layout and batch-merge receipt. All five layouts and all four query shapes passed exact stable-scan validation. Candidate losses remain visible below.

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
| FastHash batch32 allocation | 472.018 B/op | <= 500.000 | pass |
| CompactSorted batch32 allocation | 472.054 B/op | <= 500.000 | pass |
| FastHash batch32 frozen-point ratio | 0.675x | descriptive cross-run control | reported |
| CompactSorted batch32 frozen-point ratio | 0.500x | descriptive cross-run control | reported |
| Flat owned bytes/source row | 10.667 | <= 12.000 | pass |
| Flat unique single speedup versus compact | 4.614x | >= 1.250x | pass |
| Flat unique batch32 speedup versus compact | 2.693x | >= 1.250x | pass |
| Grouped unique owned bytes/source row | 10.667 | <= 12.000 | pass |
| Grouped fanout-8 owned bytes/source row | 5.833 | <= 6.000 | pass |
| Grouped skew owned bytes/source row | 10.615 | reported | reported |
| Grouped fanout-8 reduction versus flat | 45.31% | >= 40.00% | pass |
| Grouped all-equal owned bytes/source row | 4.000 | <= 4.100 | pass |
| Grouped unique single speedup versus compact | 4.820x | >= 2.000x | pass |
| Grouped fanout-8 single speedup versus compact | 1.606x | >= 1.000x | pass |
| Grouped skew batch32 speedup versus flat | 2.206x | >= 1.500x | pass |
| Balanced FastHash adaptive/sort-control ratio | 0.593x | <= 0.900x | pass |
| Balanced Compact adaptive/sort-control ratio | 0.718x | <= 0.900x | pass |
| Dominant FastHash adaptive/sort-control ratio | 0.972x | <= 1.100x | pass |
| Dominant Compact adaptive/sort-control ratio | 0.771x | <= 1.100x | pass |
| Dominant Packed adaptive/sort-control ratio | 0.778x | <= 1.100x | pass |
| Dominant Flat adaptive/sort-control ratio | 0.843x | <= 1.100x | pass |
| Maximum skew batch32 allocation | 62977.029 B/op | <= 70000.000 B/op | pass |
| Maximum skew sort-control allocation | 94889.395 B/op | same-run control | reported |
| FastHash fanout ratio versus R5g | 0.605x | descriptive cross-run control | reported |
| Compact fanout ratio versus R5g | 0.723x | descriptive cross-run control | reported |
| FastHash skew ratio versus R5g | 1.023x | descriptive cross-run control | reported |
| Compact skew ratio versus R5g | 0.777x | descriptive cross-run control | reported |
| Packed skew ratio versus R5g | 0.771x | descriptive cross-run control | reported |
| Flat skew ratio versus R5g | 0.888x | descriptive cross-run control | reported |
| Flat skewed single ratio versus compact | 0.236x | reported loss/win | reported |
| Flat skewed batch32 ratio versus compact | 0.353x | reported loss/win | reported |

The gate comparator is the exact primitive-sort strategy with the same
backend, fixture, counting pass, JVM, and process. Frozen R5g points remain
visible as descriptive provenance, not host-dependent admission gates. The
adaptive path selects heap merging for pointer-cheap balanced hash streams,
direct merging for ordered dominant streams, and sorting where traversal
cost makes either merge slower.

## Full shape matrix

| Benchmark | Input rows | Mode | Time or throughput | Allocation | Output rows | Checksum or owned bytes | Layout | Shape |
|---|---:|---:|---:|---:|---:|---:|---|---|
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 17.6311 ms/op | 20779157.965 B/op | 1000000 | `20777216` | fast-hash | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 17.4528 ms/op | 20779191.108 B/op | 1000000 | `20777216` | fast-hash | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 11.8684 ms/op | 20778861.387 B/op | 1000000 | `20777216` | fast-hash | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 16.8413 ms/op | 20779115.209 B/op | 1000000 | `20777216` | fast-hash | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 18.7338 ms/op | 16003130.178 B/op | 1000000 | `8000000` | compact-sorted | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 18.4384 ms/op | 16003086.138 B/op | 1000000 | `8000000` | compact-sorted | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 14.4504 ms/op | 16002807.644 B/op | 1000000 | `8000000` | compact-sorted | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 8.29321 ms/op | 16002394.636 B/op | 1000000 | `8000000` | compact-sorted | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 19.9092 ms/op | 18503255.385 B/op | 1000000 | `6500000` | packed-sorted | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 19.5443 ms/op | 18503253.304 B/op | 1000000 | `6500000` | packed-sorted | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 15.5591 ms/op | 18502965.818 B/op | 1000000 | `6500000` | packed-sorted | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 9.79596 ms/op | 18502590.620 B/op | 1000000 | `6500000` | packed-sorted | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 19.1572 ms/op | 10701525.194 B/op | 1000000 | `10666672` | flat-hash-rows | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 22.9004 ms/op | 10701758.855 B/op | 1000000 | `10666672` | flat-hash-rows | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 34.8297 ms/op | 10702455.393 B/op | 1000000 | `10666672` | flat-hash-rows | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 67.2322 ms/op | 10704663.000 B/op | 1000000 | `10666672` | flat-hash-rows | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 33.1082 ms/op | 26670770.300 B/op | 1000000 | `10666672` | grouped-hash | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 32.6414 ms/op | 26670770.300 B/op | 1000000 | `10666672` | grouped-hash | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 16.8620 ms/op | 21836394.897 B/op | 1000000 | `5833336` | grouped-hash | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 25.7914 ms/op | 26618263.124 B/op | 1000000 | `10614600` | grouped-hash | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000259659 ms/op | 472.018 B/op | 32 | `14554966086508252864` | fast-hash | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000169973 ms/op | 344.012 B/op | 16 | `7260442535237621654` | fast-hash | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00156250 ms/op | 2264.111 B/op | 256 | `11883708537156009700` | fast-hash | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.129735 ms/op | 62977.029 B/op | 7842 | `16398796646659458615` | fast-hash | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000760663 ms/op | 472.054 B/op | 32 | `14554966086508252864` | compact-sorted | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000674202 ms/op | 344.042 B/op | 16 | `7260442535237621654` | compact-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00208548 ms/op | 2264.147 B/op | 256 | `11883708537156009700` | compact-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0257062 ms/op | 62969.837 B/op | 7842 | `16398796646659458615` | compact-sorted | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000996570 ms/op | 472.073 B/op | 32 | `14554966086508252864` | packed-sorted | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000621324 ms/op | 344.043 B/op | 16 | `7260442535237621654` | packed-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00316494 ms/op | 2264.230 B/op | 256 | `11883708537156009700` | packed-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0294947 ms/op | 62970.125 B/op | 7842 | `16398796646659458615` | packed-sorted | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000282443 ms/op | 472.020 B/op | 32 | `14554966086508252864` | flat-hash-rows | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000256457 ms/op | 344.018 B/op | 16 | `7260442535237621654` | flat-hash-rows | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00306811 ms/op | 2264.215 B/op | 256 | `11883708537156009700` | flat-hash-rows | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0728067 ms/op | 62973.123 B/op | 7842 | `16398796646659458615` | flat-hash-rows | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000269026 ms/op | 472.019 B/op | 32 | `14554966086508252864` | grouped-hash | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000295454 ms/op | 344.021 B/op | 16 | `7260442535237621654` | grouped-hash | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00279120 ms/op | 2288.196 B/op | 256 | `11883708537156009700` | grouped-hash | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0330111 ms/op | 62970.331 B/op | 7842 | `16398796646659458615` | grouped-hash | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.37304e-06 ms/op | 0.000 B/op | 1 | `1000030` | fast-hash | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.07996e-06 ms/op | 0.000 B/op | 0 | `0` | fast-hash | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 3.89179e-05 ms/op | 96.003 B/op | 8 | `3188334135560712` | fast-hash | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.216267 ms/op | 62626.848 B/op | 7813 | `14591963101091370202` | fast-hash | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 2.37067e-05 ms/op | 0.001 B/op | 1 | `1000030` | compact-sorted | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.04584e-05 ms/op | 0.001 B/op | 0 | `0` | compact-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 3.03200e-05 ms/op | 96.002 B/op | 8 | `3188334135560712` | compact-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0170337 ms/op | 62609.281 B/op | 7813 | `14591963101091370202` | compact-sorted | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.51860e-05 ms/op | 0.001 B/op | 1 | `1000030` | packed-sorted | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.04836e-05 ms/op | 0.001 B/op | 0 | `0` | packed-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 3.57758e-05 ms/op | 96.003 B/op | 8 | `3188334135560712` | packed-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0254582 ms/op | 62609.815 B/op | 7813 | `14591963101091370202` | packed-sorted | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.13752e-06 ms/op | 0.000 B/op | 1 | `1000030` | flat-hash-rows | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 4.75131e-06 ms/op | 0.000 B/op | 0 | `0` | flat-hash-rows | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 4.84661e-05 ms/op | 120.003 B/op | 8 | `3188334135560712` | flat-hash-rows | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0723193 ms/op | 62613.051 B/op | 7813 | `14591963101091370202` | flat-hash-rows | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 4.91821e-06 ms/op | 0.000 B/op | 1 | `1000030` | grouped-hash | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 4.78533e-06 ms/op | 0.000 B/op | 0 | `0` | grouped-hash | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.88841e-05 ms/op | 96.001 B/op | 8 | `3188334135560712` | grouped-hash | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0216977 ms/op | 62609.536 B/op | 7813 | `14591963101091370202` | grouped-hash | skewed |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.000258741 ms/op | 472.018 B/op | 32 | `14554966086508252864` | fast-hash | unique |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.000176485 ms/op | 304.013 B/op | 16 | `7260442535237621654` | fast-hash | mixed-miss |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.00263358 ms/op | 2224.186 B/op | 256 | `11883708537156009700` | fast-hash | fanout-8 |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.133484 ms/op | 94889.395 B/op | 7842 | `16398796646659458615` | fast-hash | skewed |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.000807550 ms/op | 472.055 B/op | 32 | `14554966086508252864` | compact-sorted | unique |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.000548524 ms/op | 344.039 B/op | 16 | `7260442535237621654` | compact-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.00290577 ms/op | 2264.206 B/op | 256 | `11883708537156009700` | compact-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.0333594 ms/op | 94882.361 B/op | 7842 | `16398796646659458615` | compact-sorted | skewed |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.000762673 ms/op | 472.055 B/op | 32 | `14554966086508252864` | packed-sorted | unique |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.000540914 ms/op | 344.039 B/op | 16 | `7260442535237621654` | packed-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.00307476 ms/op | 2264.221 B/op | 256 | `11883708537156009700` | packed-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.0379303 ms/op | 94882.774 B/op | 7842 | `16398796646659458615` | packed-sorted | skewed |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.000285531 ms/op | 472.020 B/op | 32 | `14554966086508252864` | flat-hash-rows | unique |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.000251426 ms/op | 344.018 B/op | 16 | `7260442535237621654` | flat-hash-rows | mixed-miss |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.00338411 ms/op | 2288.228 B/op | 256 | `11883708537156009700` | flat-hash-rows | fanout-8 |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.0863371 ms/op | 94886.113 B/op | 7842 | `16398796646659458615` | flat-hash-rows | skewed |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.000341703 ms/op | 472.020 B/op | 32 | `14554966086508252864` | grouped-hash | unique |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.000249316 ms/op | 344.018 B/op | 16 | `7260442535237621654` | grouped-hash | mixed-miss |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.00267355 ms/op | 2264.189 B/op | 256 | `11883708537156009700` | grouped-hash | fanout-8 |
| `ExtendedIndexLookupCourt.sortedBatchControl` | 1000000 | avgt | 0.0404010 ms/op | 94882.884 B/op | 7842 | `16398796646659458615` | grouped-hash | skewed |
| `FlatAllEqualBuildCourt.build` | 1000000 | avgt | 5.90786 ms/op | 10700616.599 B/op | 1000000 | `10666672` | flat-hash-rows | all-equal |
| `FlatAllEqualBuildCourt.build` | 1000000 | avgt | 8.09754 ms/op | 20002561.852 B/op | 1000000 | `4000132` | grouped-hash | all-equal |

`owned bytes` are retained arrays returned by each build. JMH allocation
for `ExtendedIndexBuildCourt.build` includes temporary radix arrays and
packing. Raw JMH output is under `raw/`; `validation.tsv` records layout,
shape, output cardinality, and checksum. The frozen scan provenance is
`2026-07-26-r5e-index-baseline/summary.md`.
