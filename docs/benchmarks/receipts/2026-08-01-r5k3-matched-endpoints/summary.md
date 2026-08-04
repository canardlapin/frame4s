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
| 1000000 | both-shuffled | deep-materialized | 66.632 (50.425–69.517) | 38.569 (38.377–50.431) | 68.606 (67.237–72.738) | 15.622 (15.378–26.142) | 1.73x | 0.99x | 3.28x |
| 1000000 | both-shuffled | matched-consumption | 87.414 (75.584–108.423) | 45.728 (38.672–51.545) | 69.122 (66.182–71.301) | 17.553 (16.416–17.999) | 2.26x | 1.32x | 4.86x |
| 1000000 | right-shuffled | deep-materialized | 70.140 (49.693–81.498) | 30.942 (28.780–46.561) | 66.762 (65.625–69.882) | 19.853 (16.706–25.849) | 2.27x | 1.05x | 3.15x |
| 1000000 | right-shuffled | matched-consumption | 83.860 (73.223–84.088) | 33.455 (32.256–58.963) | 70.740 (67.246–73.045) | 20.016 (18.285–24.414) | 2.51x | 1.19x | 3.66x |
| 1000000 | sorted | deep-materialized | 40.506 (38.866–41.002) | 1.975 (1.928–2.371) | 52.300 (46.444–53.381) | 11.168 (10.093–12.455) | 20.51x | 0.77x | 3.48x |
| 1000000 | sorted | matched-consumption | 63.313 (62.439–65.111) | 2.788 (2.543–3.334) | 52.475 (51.985–53.389) | 12.136 (10.504–12.390) | 23.35x | 1.22x | 5.15x |
| 4000000 | both-shuffled | deep-materialized | 484.593 (410.996–510.866) | 286.753 (271.220–288.787) | 365.912 (335.430–370.038) | 91.498 (89.569–94.507) | 1.68x | 1.40x | 5.30x |
| 4000000 | both-shuffled | matched-consumption | 571.020 (509.367–629.196) | 291.227 (272.465–291.528) | 341.119 (327.933–389.207) | 96.505 (95.529–98.252) | 2.10x | 1.55x | 5.81x |
| 4000000 | right-shuffled | deep-materialized | 485.608 (374.780–533.457) | 224.281 (220.259–234.739) | 359.534 (357.576–378.090) | 97.472 (90.630–108.335) | 2.20x | 1.35x | 4.92x |
| 4000000 | right-shuffled | matched-consumption | 546.861 (492.564–560.936) | 226.864 (221.706–232.907) | 371.761 (341.435–399.534) | 99.516 (94.813–107.750) | 2.35x | 1.47x | 5.21x |
| 4000000 | sorted | deep-materialized | 183.482 (180.470–189.752) | 7.991 (7.706–8.460) | 269.904 (259.371–288.672) | 58.623 (57.606–60.310) | 22.96x | 0.68x | 3.15x |
| 4000000 | sorted | matched-consumption | 276.802 (273.492–294.281) | 11.231 (10.627–11.340) | 289.612 (277.573–294.036) | 61.027 (58.664–71.054) | 26.05x | 1.00x | 4.48x |

## frame4s-only diagnostics

`gather-view` is the old internal endpoint and has no pandas or Polars
ratio. `prepare` isolates normalization and physical classification.

| Rows | Order | Endpoint | Median ms | Process range |
|---:|---|---|---:|---:|
| 1000000 | both-shuffled | prepare | 0.001 | 0.001–0.002 |
| 1000000 | both-shuffled | gather-view | 10.635 | 9.671–10.866 |
| 1000000 | right-shuffled | prepare | 0.001 | 0.001–0.002 |
| 1000000 | right-shuffled | gather-view | 10.422 | 9.575–10.558 |
| 1000000 | sorted | prepare | 0.001 | 0.001–0.001 |
| 1000000 | sorted | gather-view | 10.063 | 9.901–10.278 |
| 4000000 | both-shuffled | prepare | 0.001 | 0.001–0.003 |
| 4000000 | both-shuffled | gather-view | 72.559 | 63.997–72.761 |
| 4000000 | right-shuffled | prepare | 0.001 | 0.001–0.002 |
| 4000000 | right-shuffled | gather-view | 72.588 | 65.933–73.292 |
| 4000000 | sorted | prepare | 0.001 | 0.001–0.002 |
| 4000000 | sorted | gather-view | 49.962 | 49.675–65.065 |

## frame4s allocation

Allocation is the median normalized JMH allocation across process
rounds. The process range remains visible because deep materialization
can expose fork-specific escape-analysis and garbage-collection effects.

| Rows | Order | Endpoint | Median MB/op | Process range MB/op |
|---:|---|---|---:|---:|
| 1000000 | both-shuffled | prepare | 0.01 | 0.01–0.01 |
| 1000000 | both-shuffled | gather-view | 48.82 | 48.82–48.82 |
| 1000000 | both-shuffled | deep-materialized | 73.45 | 73.44–73.45 |
| 1000000 | both-shuffled | matched-consumption | 73.45 | 73.45–73.46 |
| 1000000 | right-shuffled | prepare | 0.01 | 0.01–0.01 |
| 1000000 | right-shuffled | gather-view | 48.82 | 48.82–48.82 |
| 1000000 | right-shuffled | deep-materialized | 73.45 | 73.44–73.45 |
| 1000000 | right-shuffled | matched-consumption | 73.45 | 73.45–73.45 |
| 1000000 | sorted | prepare | 0.01 | 0.01–0.01 |
| 1000000 | sorted | gather-view | 32.01 | 32.01–32.01 |
| 1000000 | sorted | deep-materialized | 56.51 | 56.51–56.51 |
| 1000000 | sorted | matched-consumption | 56.51 | 56.51–56.51 |
| 4000000 | both-shuffled | prepare | 0.01 | 0.01–0.01 |
| 4000000 | both-shuffled | gather-view | 195.16 | 195.16–195.16 |
| 4000000 | both-shuffled | deep-materialized | 293.32 | 293.32–293.33 |
| 4000000 | both-shuffled | matched-consumption | 293.33 | 293.33–293.33 |
| 4000000 | right-shuffled | prepare | 0.01 | 0.01–0.01 |
| 4000000 | right-shuffled | gather-view | 195.16 | 195.16–195.16 |
| 4000000 | right-shuffled | deep-materialized | 293.32 | 293.32–293.33 |
| 4000000 | right-shuffled | matched-consumption | 293.33 | 293.32–293.33 |
| 4000000 | sorted | prepare | 0.01 | 0.01–0.01 |
| 4000000 | sorted | gather-view | 96.01 | 96.01–96.01 |
| 4000000 | sorted | deep-materialized | 194.02 | 194.02–194.02 |
| 4000000 | sorted | matched-consumption | 194.02 | 194.02–194.02 |

`validation.tsv` proves identical schema, cardinality, exact ordered
column bytes, full-column sum, and input fingerprints. Component
directories retain raw samples, allocation, versions, thread counts,
stage attribution, and sequence position.
