# frame4s R5g extended secondary-index court

Full R5g extended-layout receipt. All four layouts and all four query shapes passed exact stable-scan validation. Candidate losses remain visible below.

## Decisions

- `PackedSorted` is **admitted as the lower-memory sorted layout**.
- The clone-once/token-reuse batch engine is **admitted as the batch lookup path**.
- `FlatHashRows` is **admitted as an explicit low-fanout balanced layout; not as a skewed-key layout**.
- `FastHash` remains the default; this court does not infer an automatic
  layout policy.

## Precommitted gates

| Gate | Result | Threshold | Status |
|---|---:|---:|---:|
| Packed owned bytes/source row | 6.500 | <= 6.750 | pass |
| Packed reduction versus compact | 18.75% | >= 15.00% | pass |
| FastHash batch32 allocation | 432.018 B/op | <= 500.000 | pass |
| CompactSorted batch32 allocation | 472.049 B/op | <= 500.000 | pass |
| FastHash batch32 frozen-point ratio | 0.667x | <= 1.100x | pass |
| CompactSorted batch32 frozen-point ratio | 0.463x | <= 1.100x | pass |
| Flat owned bytes/source row | 10.667 | <= 12.000 | pass |
| Flat unique single speedup versus compact | 2.720x | >= 1.250x | pass |
| Flat unique batch32 speedup versus compact | 2.575x | >= 1.250x | pass |
| Flat skewed single ratio versus compact | 0.215x | reported loss/win | reported |
| Flat skewed batch32 ratio versus compact | 0.404x | reported loss/win | reported |

The two frozen-point ratios are conservative cross-run guardrails. Same-run
layout rankings come from this receipt; a host/JDK difference is not
presented as a causal code regression. Ratios below 1.0 on the two skew
rows mean `CompactSorted` is faster; those losses limit the flat layout's
admission even though they do not rewrite the precommitted unique gates.

## Full shape matrix

| Benchmark | Input rows | Mode | Time or throughput | Allocation | Output rows | Checksum or owned bytes | Layout | Shape |
|---|---:|---:|---:|---:|---:|---:|---|---|
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 15.9756 ms/op | 20779049.730 B/op | 1000000 | `20777216` | fast-hash | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 15.8184 ms/op | 20779048.405 B/op | 1000000 | `20777216` | fast-hash | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 11.5288 ms/op | 20778744.220 B/op | 1000000 | `20777216` | fast-hash | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 16.0003 ms/op | 20779050.976 B/op | 1000000 | `20777216` | fast-hash | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 18.5998 ms/op | 16003172.400 B/op | 1000000 | `8000000` | compact-sorted | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 18.5199 ms/op | 16003217.378 B/op | 1000000 | `8000000` | compact-sorted | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 14.1606 ms/op | 16002870.327 B/op | 1000000 | `8000000` | compact-sorted | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 8.52209 ms/op | 16002404.737 B/op | 1000000 | `8000000` | compact-sorted | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 19.5325 ms/op | 18503210.190 B/op | 1000000 | `6500000` | packed-sorted | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 19.8262 ms/op | 18503255.138 B/op | 1000000 | `6500000` | packed-sorted | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 15.3646 ms/op | 18502935.130 B/op | 1000000 | `6500000` | packed-sorted | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 9.41200 ms/op | 18502553.149 B/op | 1000000 | `6500000` | packed-sorted | skewed |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 21.6434 ms/op | 10701688.600 B/op | 1000000 | `10666672` | flat-hash-rows | unique |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 21.5424 ms/op | 10701638.352 B/op | 1000000 | `10666672` | flat-hash-rows | mixed-miss |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 33.3522 ms/op | 10702438.033 B/op | 1000000 | `10666672` | flat-hash-rows | fanout-8 |
| `ExtendedIndexBuildCourt.build` | 1000000 | avgt | 66.0687 ms/op | 10704663.000 B/op | 1000000 | `10666672` | flat-hash-rows | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000256582 ms/op | 432.018 B/op | 32 | `14554966086508252864` | fast-hash | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000182533 ms/op | 304.013 B/op | 16 | `7260442535237621654` | fast-hash | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00258322 ms/op | 2224.181 B/op | 256 | `11883708537156009700` | fast-hash | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.126760 ms/op | 94888.930 B/op | 7842 | `16398796646659458615` | fast-hash | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000704673 ms/op | 472.049 B/op | 32 | `14554966086508252864` | compact-sorted | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000536622 ms/op | 344.038 B/op | 16 | `7260442535237621654` | compact-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00288340 ms/op | 2264.203 B/op | 256 | `11883708537156009700` | compact-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0330881 ms/op | 94882.307 B/op | 7842 | `16398796646659458615` | compact-sorted | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000739756 ms/op | 472.053 B/op | 32 | `14554966086508252864` | packed-sorted | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000537756 ms/op | 344.039 B/op | 16 | `7260442535237621654` | packed-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00301347 ms/op | 2264.217 B/op | 256 | `11883708537156009700` | packed-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0382594 ms/op | 94882.719 B/op | 7842 | `16398796646659458615` | packed-sorted | skewed |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000273659 ms/op | 472.019 B/op | 32 | `14554966086508252864` | flat-hash-rows | unique |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.000254181 ms/op | 344.018 B/op | 16 | `7260442535237621654` | flat-hash-rows | mixed-miss |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.00302643 ms/op | 2264.215 B/op | 256 | `11883708537156009700` | flat-hash-rows | fanout-8 |
| `ExtendedIndexLookupCourt.batch32Lookup` | 1000000 | avgt | 0.0819962 ms/op | 94885.804 B/op | 7842 | `16398796646659458615` | flat-hash-rows | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.36095e-06 ms/op | 0.000 B/op | 1 | `1000030` | fast-hash | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.03271e-06 ms/op | 0.000 B/op | 0 | `0` | fast-hash | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 3.74453e-05 ms/op | 96.003 B/op | 8 | `3188334135560712` | fast-hash | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.116081 ms/op | 62616.100 B/op | 7813 | `14591963101091370202` | fast-hash | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.44448e-05 ms/op | 0.001 B/op | 1 | `1000030` | compact-sorted | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.05242e-05 ms/op | 0.001 B/op | 0 | `0` | compact-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 2.97731e-05 ms/op | 96.002 B/op | 8 | `3188334135560712` | compact-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0174677 ms/op | 62609.229 B/op | 7813 | `14591963101091370202` | compact-sorted | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.50496e-05 ms/op | 0.001 B/op | 1 | `1000030` | packed-sorted | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 1.05939e-05 ms/op | 0.001 B/op | 0 | `0` | packed-sorted | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 3.57683e-05 ms/op | 96.003 B/op | 8 | `3188334135560712` | packed-sorted | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0262480 ms/op | 62609.844 B/op | 7813 | `14591963101091370202` | packed-sorted | skewed |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.31016e-06 ms/op | 0.000 B/op | 1 | `1000030` | flat-hash-rows | unique |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 5.37778e-06 ms/op | 0.000 B/op | 0 | `0` | flat-hash-rows | mixed-miss |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 6.03189e-05 ms/op | 120.004 B/op | 8 | `3188334135560712` | flat-hash-rows | fanout-8 |
| `ExtendedIndexLookupCourt.singleLookup` | 1000000 | avgt | 0.0812519 ms/op | 62613.766 B/op | 7813 | `14591963101091370202` | flat-hash-rows | skewed |
| `FlatAllEqualBuildCourt.build` | 1000000 | avgt | 5.70288 ms/op | 10700595.205 B/op | 1000000 | `10666672` | flat-hash-rows | all-equal |

`owned bytes` are retained arrays returned by each build. JMH allocation
for `ExtendedIndexBuildCourt.build` includes temporary radix arrays and
packing. Raw JMH output is under `raw/`; `validation.tsv` records layout,
shape, output cardinality, and checksum. The frozen scan provenance is
`baseline/summary.md`.
