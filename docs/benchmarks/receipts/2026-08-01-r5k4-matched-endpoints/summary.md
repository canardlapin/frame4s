# Matched join endpoint court

Results are medians across 3 interleaved process-level rounds.
Parenthesized ranges are the minimum and maximum round medians. Ratios are
paired within each process round before aggregation.

Every backend returns `key: Int32`, `leftValue: Int64`, `rightKey: Int32`,
and `rightValue: Int64` in exact stable-left order. Input construction is
outside timing. `deep-materialized` is the only construction comparator;
`matched-consumption` additionally sums all four materialized columns.

| Rows | Order | Endpoint | frame4s ms | pandas ms | Polars 1t ms | Polars default ms | f4s/pandas | f4s/Polars 1t | f4s/Polars default |
|---:|---|---|---:|---:|---:|---:|---:|---:|---:|
| 1000000 | both-shuffled | deep-materialized | 40.385 (40.201–41.621) | 26.896 (26.808–27.756) | 54.936 (54.615–55.843) | 15.226 (15.104–15.409) | 1.50x | 0.74x | 2.66x |
| 1000000 | both-shuffled | matched-consumption | 62.536 (61.615–63.671) | 28.161 (28.141–30.145) | 56.945 (56.543–58.039) | 15.840 (15.188–16.044) | 2.22x | 1.11x | 4.02x |
| 1000000 | right-shuffled | deep-materialized | 40.171 (40.000–40.712) | 24.751 (24.589–24.809) | 56.208 (55.353–56.700) | 15.195 (15.119–15.590) | 1.63x | 0.72x | 2.63x |
| 1000000 | right-shuffled | matched-consumption | 63.472 (63.070–64.065) | 25.295 (25.035–25.538) | 56.235 (55.379–59.103) | 15.904 (15.198–15.966) | 2.49x | 1.12x | 4.03x |
| 1000000 | sorted | deep-materialized | 12.749 (12.630–12.759) | 1.819 (1.811–1.823) | 41.374 (40.909–41.667) | 9.528 (9.452–9.724) | 7.02x | 0.31x | 1.34x |
| 1000000 | sorted | matched-consumption | 36.062 (36.057–36.100) | 2.398 (2.395–2.404) | 41.380 (40.477–41.510) | 10.637 (10.205–10.642) | 15.05x | 0.87x | 3.39x |
| 4000000 | both-shuffled | deep-materialized | 299.421 (274.795–312.246) | 214.846 (213.273–215.369) | 288.477 (282.770–290.658) | 91.411 (89.666–92.109) | 1.40x | 1.03x | 3.28x |
| 4000000 | both-shuffled | matched-consumption | 388.497 (376.223–392.298) | 215.491 (214.498–217.148) | 287.611 (284.807–293.533) | 94.637 (93.010–94.972) | 1.79x | 1.35x | 4.11x |
| 4000000 | right-shuffled | deep-materialized | 301.017 (287.803–303.148) | 171.438 (170.819–173.557) | 286.276 (281.559–303.762) | 90.990 (89.414–91.895) | 1.75x | 1.02x | 3.28x |
| 4000000 | right-shuffled | matched-consumption | 391.301 (363.429–393.451) | 174.243 (170.036–174.861) | 293.982 (286.266–294.663) | 94.282 (93.015–94.677) | 2.25x | 1.33x | 4.16x |
| 4000000 | sorted | deep-materialized | 58.566 (58.362–58.622) | 7.149 (7.086–7.158) | 202.830 (202.475–203.981) | 59.682 (57.769–60.220) | 8.19x | 0.29x | 0.98x |
| 4000000 | sorted | matched-consumption | 150.781 (150.768–150.923) | 9.833 (9.715–9.840) | 211.844 (210.265–212.424) | 60.731 (60.574–64.337) | 15.33x | 0.71x | 2.49x |

## frame4s-only diagnostics

`gather-view` is the old internal endpoint and has no pandas or Polars
ratio. `prepare` isolates normalization and physical classification.

| Rows | Order | Endpoint | Median ms | Process range |
|---:|---|---|---:|---:|
| 1000000 | both-shuffled | prepare | 0.001 | 0.001–0.001 |
| 1000000 | both-shuffled | gather-view | 9.767 | 9.706–9.843 |
| 1000000 | right-shuffled | prepare | 0.001 | 0.001–0.001 |
| 1000000 | right-shuffled | gather-view | 9.687 | 9.676–9.786 |
| 1000000 | sorted | prepare | 0.001 | 0.001–0.001 |
| 1000000 | sorted | gather-view | 9.797 | 9.774–9.834 |
| 4000000 | both-shuffled | prepare | 0.001 | 0.001–0.001 |
| 4000000 | both-shuffled | gather-view | 63.538 | 62.898–63.906 |
| 4000000 | right-shuffled | prepare | 0.001 | 0.001–0.001 |
| 4000000 | right-shuffled | gather-view | 62.848 | 62.585–63.132 |
| 4000000 | sorted | prepare | 0.001 | 0.001–0.001 |
| 4000000 | sorted | gather-view | 47.792 | 47.764–48.332 |

## frame4s allocation

Allocation is the median normalized JMH allocation across process
rounds. The process range remains visible because deep materialization
can expose fork-specific escape-analysis and garbage-collection effects.

| Rows | Order | Endpoint | Median MB/op | Process range MB/op |
|---:|---|---|---:|---:|
| 1000000 | both-shuffled | prepare | 0.01 | 0.01–0.01 |
| 1000000 | both-shuffled | gather-view | 48.82 | 48.82–48.82 |
| 1000000 | both-shuffled | deep-materialized | 73.42 | 73.42–73.42 |
| 1000000 | both-shuffled | matched-consumption | 73.43 | 73.43–73.43 |
| 1000000 | right-shuffled | prepare | 0.01 | 0.01–0.01 |
| 1000000 | right-shuffled | gather-view | 48.82 | 48.82–48.82 |
| 1000000 | right-shuffled | deep-materialized | 73.42 | 73.42–73.42 |
| 1000000 | right-shuffled | matched-consumption | 73.43 | 73.43–73.44 |
| 1000000 | sorted | prepare | 0.01 | 0.01–0.01 |
| 1000000 | sorted | gather-view | 32.01 | 32.01–32.01 |
| 1000000 | sorted | deep-materialized | 56.53 | 56.53–56.53 |
| 1000000 | sorted | matched-consumption | 56.54 | 56.54–56.54 |
| 4000000 | both-shuffled | prepare | 0.01 | 0.01–0.01 |
| 4000000 | both-shuffled | gather-view | 195.16 | 195.16–195.16 |
| 4000000 | both-shuffled | deep-materialized | 293.35 | 293.35–293.35 |
| 4000000 | both-shuffled | matched-consumption | 293.35 | 293.35–293.35 |
| 4000000 | right-shuffled | prepare | 0.01 | 0.01–0.01 |
| 4000000 | right-shuffled | gather-view | 195.16 | 195.16–195.16 |
| 4000000 | right-shuffled | deep-materialized | 293.35 | 293.35–293.35 |
| 4000000 | right-shuffled | matched-consumption | 293.35 | 293.35–293.35 |
| 4000000 | sorted | prepare | 0.01 | 0.01–0.01 |
| 4000000 | sorted | gather-view | 96.01 | 96.01–96.01 |
| 4000000 | sorted | deep-materialized | 194.04 | 194.04–194.04 |
| 4000000 | sorted | matched-consumption | 194.04 | 194.04–194.04 |

`validation.tsv` proves identical schema, cardinality, exact ordered
column bytes, full-column sum, and input fingerprints. Component
directories retain raw samples, allocation, versions, thread counts,
stage attribution, and sequence position.
