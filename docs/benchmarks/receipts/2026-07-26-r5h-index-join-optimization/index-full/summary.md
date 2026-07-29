# frame4s R5h extended secondary-index court

Full R5h grouped-layout and batch-merge receipt. All five layouts and all four query shapes passed exact stable-scan validation. Candidate losses remain visible below.

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
| FastHash batch32 allocation | 472.041 B/op | <= 500.000 | pass |
| CompactSorted batch32 allocation | 472.099 B/op | <= 500.000 | pass |
| FastHash batch32 frozen-point ratio | 1.293x | <= 1.100x | fail |
| CompactSorted batch32 frozen-point ratio | 0.986x | <= 1.100x | pass |
| Flat owned bytes/source row | 10.667 | <= 12.000 | pass |
| Flat unique single speedup versus compact | 2.821x | >= 1.250x | pass |
| Flat unique batch32 speedup versus compact | 3.588x | >= 1.250x | pass |
| Grouped unique owned bytes/source row | 10.667 | <= 12.000 | pass |
| Grouped fanout-8 owned bytes/source row | 5.833 | <= 6.000 | pass |
| Grouped skew owned bytes/source row | 10.615 | reported | reported |
| Grouped fanout-8 reduction versus flat | 45.31% | >= 40.00% | pass |
| Grouped all-equal owned bytes/source row | 4.000 | <= 4.100 | pass |
| Grouped unique single speedup versus compact | 2.164x | >= 2.000x | pass |
| Grouped fanout-8 single speedup versus compact | 1.207x | >= 1.000x | pass |
| Grouped skew batch32 speedup versus flat | 2.610x | >= 1.500x | pass |
| Balanced FastHash fanout ratio versus R5g | 0.961x | <= 0.900x | fail |
| Balanced Compact fanout ratio versus R5g | 1.377x | <= 0.900x | fail |
| Dominant FastHash skew ratio versus R5g | 1.462x | <= 1.100x | fail |
| Dominant Compact skew ratio versus R5g | 1.384x | <= 1.100x | fail |
| Dominant Packed skew ratio versus R5g | 1.609x | <= 1.100x | fail |
| Dominant Flat skew ratio versus R5g | 1.185x | <= 1.100x | fail |
| Maximum skew batch32 allocation | 62981.094 B/op | <= 70000.000 B/op | pass |
| Flat skewed single ratio versus compact | 0.205x | reported loss/win | reported |
| Flat skewed batch32 ratio versus compact | 0.471x | reported loss/win | reported |

The frozen-point ratios are conservative cross-run guardrails. The adaptive
batch path selects heap merging for pointer-cheap balanced hash streams,
direct merging for ordered dominant streams, and sorting where traversal
cost makes either merge slower. Same-run layout
rankings remain primary, and all losses remain visible.

## Full shape matrix

| Benchmark | Input rows | Mode | Time or throughput | Allocation | Output rows | Checksum or owned bytes | Layout | Shape |
|---|---:|---:|---:|---:|---:|---:|---|---|
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 18.4112 ms/op | 20779236.650 B/op | 1000000 | `20777216` | fast-hash | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 17.9395 ms/op | 20779231.570 B/op | 1000000 | `20777216` | fast-hash | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 16.5471 ms/op | 20779441.891 B/op | 1000000 | `20777216` | fast-hash | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 17.7181 ms/op | 20779192.276 B/op | 1000000 | `20777216` | fast-hash | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 20.8029 ms/op | 16003239.631 B/op | 1000000 | `8000000` | compact-sorted | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 21.8036 ms/op | 16003299.223 B/op | 1000000 | `8000000` | compact-sorted | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 14.6804 ms/op | 16002832.628 B/op | 1000000 | `8000000` | compact-sorted | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 8.13606 ms/op | 16002370.316 B/op | 1000000 | `8000000` | compact-sorted | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 20.2989 ms/op | 18503261.779 B/op | 1000000 | `6500000` | packed-sorted | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 21.0642 ms/op | 18503363.049 B/op | 1000000 | `6500000` | packed-sorted | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 16.4670 ms/op | 18503003.333 B/op | 1000000 | `6500000` | packed-sorted | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 9.75854 ms/op | 18502538.226 B/op | 1000000 | `6500000` | packed-sorted | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 31.1632 ms/op | 10702310.771 B/op | 1000000 | `10666672` | flat-hash-rows | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 38.4642 ms/op | 10702621.043 B/op | 1000000 | `10666672` | flat-hash-rows | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 64.0913 ms/op | 10703877.286 B/op | 1000000 | `10666672` | flat-hash-rows | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 132.653 ms/op | 10709057.680 B/op | 1000000 | `10666672` | flat-hash-rows | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 57.3133 ms/op | 26672150.369 B/op | 1000000 | `10666672` | grouped-hash | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 56.3217 ms/op | 26672144.850 B/op | 1000000 | `10666672` | grouped-hash | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 25.0107 ms/op | 21837057.538 B/op | 1000000 | `5833336` | grouped-hash | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 43.3313 ms/op | 26619434.432 B/op | 1000000 | `10614600` | grouped-hash | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000497569 ms/op | 472.041 B/op | 32 | `14554966086508252864` | fast-hash | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000363608 ms/op | 344.022 B/op | 16 | `7260442535237621654` | fast-hash | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00248257 ms/op | 2264.166 B/op | 256 | `11883708537156009700` | fast-hash | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.185340 ms/op | 62981.094 B/op | 7842 | `16398796646659458615` | fast-hash | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00149994 ms/op | 472.099 B/op | 32 | `14554966086508252864` | compact-sorted | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00106173 ms/op | 344.058 B/op | 16 | `7260442535237621654` | compact-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00396959 ms/op | 2264.270 B/op | 256 | `11883708537156009700` | compact-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0457812 ms/op | 62971.536 B/op | 7842 | `16398796646659458615` | compact-sorted | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000931017 ms/op | 472.065 B/op | 32 | `14554966086508252864` | packed-sorted | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000925059 ms/op | 344.058 B/op | 16 | `7260442535237621654` | packed-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00576955 ms/op | 2268.205 B/op | 256 | `11883708537156009700` | packed-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0615690 ms/op | 62971.557 B/op | 7842 | `16398796646659458615` | packed-sorted | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000418035 ms/op | 472.028 B/op | 32 | `14554966086508252864` | flat-hash-rows | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000280811 ms/op | 344.019 B/op | 16 | `7260442535237621654` | flat-hash-rows | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00327682 ms/op | 2264.235 B/op | 256 | `11883708537156009700` | flat-hash-rows | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0971732 ms/op | 62974.666 B/op | 7842 | `16398796646659458615` | flat-hash-rows | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000328974 ms/op | 472.025 B/op | 32 | `14554966086508252864` | grouped-hash | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000284740 ms/op | 344.020 B/op | 16 | `7260442535237621654` | grouped-hash | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00297970 ms/op | 2288.209 B/op | 256 | `11883708537156009700` | grouped-hash | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0372323 ms/op | 62970.648 B/op | 7842 | `16398796646659458615` | grouped-hash | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 7.07087e-06 ms/op | 0.000 B/op | 1 | `1000030` | fast-hash | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.35788e-06 ms/op | 0.000 B/op | 0 | `0` | fast-hash | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 4.16638e-05 ms/op | 96.003 B/op | 8 | `3188334135560712` | fast-hash | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.157675 ms/op | 62618.385 B/op | 7813 | `14591963101091370202` | fast-hash | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.53898e-05 ms/op | 0.001 B/op | 1 | `1000030` | compact-sorted | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.11273e-05 ms/op | 0.001 B/op | 0 | `0` | compact-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 3.13323e-05 ms/op | 96.002 B/op | 8 | `3188334135560712` | compact-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0249905 ms/op | 62609.602 B/op | 7813 | `14591963101091370202` | compact-sorted | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.84780e-05 ms/op | 0.001 B/op | 1 | `1000030` | packed-sorted | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.11001e-05 ms/op | 0.001 B/op | 0 | `0` | packed-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 3.77773e-05 ms/op | 96.003 B/op | 8 | `3188334135560712` | packed-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0303134 ms/op | 62610.012 B/op | 7813 | `14591963101091370202` | packed-sorted | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.45484e-06 ms/op | 0.000 B/op | 1 | `1000030` | flat-hash-rows | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 4.95628e-06 ms/op | 0.000 B/op | 0 | `0` | flat-hash-rows | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.89181e-05 ms/op | 136.004 B/op | 8 | `3188334135560712` | flat-hash-rows | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.121867 ms/op | 62615.613 B/op | 7813 | `14591963101091370202` | flat-hash-rows | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 7.11204e-06 ms/op | 0.001 B/op | 1 | `1000030` | grouped-hash | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 9.91072e-06 ms/op | 0.001 B/op | 0 | `0` | grouped-hash | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 2.59563e-05 ms/op | 96.002 B/op | 8 | `3188334135560712` | grouped-hash | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0235667 ms/op | 62609.630 B/op | 7813 | `14591963101091370202` | grouped-hash | skewed |
| `FlatAllEqualBuildCourt.build` | 1000000 | avgt | 6.12453 ms/op | 10700616.028 B/op | 1000000 | `10666672` | flat-hash-rows | all-equal |
| `FlatAllEqualBuildCourt.build` | 1000000 | avgt | 20.0252 ms/op | 20003297.548 B/op | 1000000 | `4000132` | grouped-hash | all-equal |

`owned bytes` are retained arrays returned by each build. JMH allocation
for `ExtendedIndexBuildCourt.build` includes temporary radix arrays and
packing. Raw JMH output is under `raw/`; `validation.tsv` records layout,
shape, output cardinality, and checksum. The frozen scan provenance is
`2026-07-26-r5e-index-baseline/summary.md`.
