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
| 1000000 | both-shuffled | deep-materialized | 112.266 (86.967–204.626) | 46.475 (46.239–74.874) | 79.022 (66.274–87.400) | 25.089 (18.646–35.710) | 2.43x | 1.69x | 5.73x |
| 1000000 | both-shuffled | matched-consumption | 160.789 (111.783–253.098) | 50.247 (45.872–81.048) | 82.775 (69.467–100.751) | 36.127 (19.397–51.350) | 2.44x | 1.60x | 3.13x |
| 1000000 | right-shuffled | deep-materialized | 159.781 (100.653–161.460) | 35.918 (32.637–41.910) | 71.758 (71.089–152.194) | 54.363 (18.451–54.394) | 3.81x | 1.42x | 2.94x |
| 1000000 | right-shuffled | matched-consumption | 228.749 (119.540–243.509) | 38.140 (38.055–61.445) | 70.955 (69.600–209.142) | 47.355 (23.906–87.042) | 3.96x | 1.68x | 5.14x |
| 1000000 | sorted | deep-materialized | 119.920 (94.831–317.060) | 2.253 (2.225–3.151) | 52.173 (51.777–79.789) | 13.997 (11.556–42.270) | 42.62x | 1.82x | 8.21x |
| 1000000 | sorted | matched-consumption | 255.810 (168.826–288.594) | 2.927 (2.851–3.730) | 52.767 (52.455–86.626) | 15.831 (11.814–23.846) | 89.72x | 4.85x | 18.23x |
| 4000000 | both-shuffled | deep-materialized | 778.983 (499.493–1005.460) | 289.000 (275.837–773.624) | 489.206 (359.173–578.160) | 131.473 (114.994–145.806) | 1.73x | 1.74x | 6.77x |
| 4000000 | both-shuffled | matched-consumption | 1498.104 (660.885–1729.202) | 341.602 (289.122–432.473) | 393.296 (371.310–623.017) | 170.369 (117.642–210.638) | 3.46x | 2.40x | 8.21x |
| 4000000 | right-shuffled | deep-materialized | 587.693 (531.776–962.130) | 277.906 (246.006–1040.143) | 379.802 (353.205–510.597) | 132.119 (102.076–157.589) | 1.91x | 1.66x | 5.76x |
| 4000000 | right-shuffled | matched-consumption | 1072.175 (726.443–1195.327) | 244.389 (218.740–1087.155) | 390.761 (372.962–664.354) | 154.391 (135.178–226.080) | 3.32x | 1.86x | 7.74x |
| 4000000 | sorted | deep-materialized | 454.436 (389.075–482.483) | 8.214 (8.019–11.645) | 285.326 (277.573–420.696) | 76.856 (61.713–99.822) | 47.37x | 1.40x | 6.28x |
| 4000000 | sorted | matched-consumption | 773.633 (659.224–897.235) | 11.720 (11.366–21.656) | 303.309 (282.258–526.448) | 72.228 (71.423–111.988) | 56.25x | 2.34x | 9.13x |

## frame4s-only diagnostics

`gather-view` is the old internal endpoint and has no pandas or Polars
ratio. `prepare` isolates normalization and physical classification.

| Rows | Order | Endpoint | Median ms | Process range |
|---:|---|---|---:|---:|
| 1000000 | both-shuffled | prepare | 0.002 | 0.002–0.002 |
| 1000000 | both-shuffled | gather-view | 27.306 | 12.832–28.848 |
| 1000000 | right-shuffled | prepare | 0.002 | 0.002–0.003 |
| 1000000 | right-shuffled | gather-view | 22.465 | 12.410–29.462 |
| 1000000 | sorted | prepare | 0.002 | 0.001–0.003 |
| 1000000 | sorted | gather-view | 9.983 | 9.655–10.662 |
| 4000000 | both-shuffled | prepare | 0.002 | 0.002–0.003 |
| 4000000 | both-shuffled | gather-view | 106.628 | 80.546–136.012 |
| 4000000 | right-shuffled | prepare | 0.002 | 0.001–0.002 |
| 4000000 | right-shuffled | gather-view | 149.687 | 81.246–152.515 |
| 4000000 | sorted | prepare | 0.002 | 0.001–0.002 |
| 4000000 | sorted | gather-view | 42.799 | 37.440–58.479 |

## frame4s allocation

Allocation is the median normalized JMH allocation across process
rounds. The process range remains visible because deep materialization
can expose fork-specific escape-analysis and garbage-collection effects.

| Rows | Order | Endpoint | Median MB/op | Process range MB/op |
|---:|---|---|---:|---:|
| 1000000 | both-shuffled | prepare | 0.01 | 0.01–0.01 |
| 1000000 | both-shuffled | gather-view | 48.82 | 48.81–48.82 |
| 1000000 | both-shuffled | deep-materialized | 221.00 | 221.00–221.01 |
| 1000000 | both-shuffled | matched-consumption | 221.00 | 220.99–221.00 |
| 1000000 | right-shuffled | prepare | 0.01 | 0.01–0.01 |
| 1000000 | right-shuffled | gather-view | 48.82 | 48.81–48.82 |
| 1000000 | right-shuffled | deep-materialized | 221.00 | 221.00–396.99 |
| 1000000 | right-shuffled | matched-consumption | 221.00 | 221.00–221.01 |
| 1000000 | sorted | prepare | 0.01 | 0.01–0.01 |
| 1000000 | sorted | gather-view | 32.01 | 32.01–32.01 |
| 1000000 | sorted | deep-materialized | 204.02 | 204.01–204.02 |
| 1000000 | sorted | matched-consumption | 204.02 | 204.02–204.02 |
| 4000000 | both-shuffled | prepare | 0.01 | 0.01–0.01 |
| 4000000 | both-shuffled | gather-view | 195.16 | 195.16–195.16 |
| 4000000 | both-shuffled | deep-materialized | 883.36 | 883.35–883.36 |
| 4000000 | both-shuffled | matched-consumption | 883.36 | 883.36–883.36 |
| 4000000 | right-shuffled | prepare | 0.01 | 0.01–0.01 |
| 4000000 | right-shuffled | gather-view | 195.16 | 195.16–195.16 |
| 4000000 | right-shuffled | deep-materialized | 883.36 | 883.36–883.36 |
| 4000000 | right-shuffled | matched-consumption | 883.36 | 883.36–883.36 |
| 4000000 | sorted | prepare | 0.01 | 0.01–0.01 |
| 4000000 | sorted | gather-view | 128.01 | 128.01–128.01 |
| 4000000 | sorted | deep-materialized | 816.03 | 816.02–1520.02 |
| 4000000 | sorted | matched-consumption | 816.03 | 816.02–816.03 |

`validation.tsv` proves identical schema, cardinality, exact ordered
column bytes, full-column sum, and input fingerprints. Component
directories retain raw samples, allocation, versions, thread counts,
stage attribution, and sequence position.
