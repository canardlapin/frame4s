# Focused 1M before-and-after court

This comparison runs the baseline at commit `1293232` and the candidate with
the same six-method JMH command, 1 GiB heap, fork count, warmup, and measurement
settings. The table uses the median of the five raw measurement iterations so
one outlying iteration cannot decide admission.

| Workload | Baseline | Candidate | Speedup | Candidate allocation |
|---|---:|---:|---:|---:|
| anti sparse | 16.897 ms | 12.882 ms | 1.312x | 0.793x |
| one-to-many | 35.877 ms | 29.024 ms | 1.236x | 0.884x |
| one-to-one | 31.646 ms | 21.505 ms | 1.472x | 0.749x |
| skewed | 2.165 ms | 1.281 ms | 1.689x | 0.999x |
| sparse | 15.384 ms | 12.393 ms | 1.241x | 0.897x |
| semi sparse | 16.533 ms | 12.284 ms | 1.346x | 0.916x |

Every measured shape is faster. Allocation is lower on five shapes and
effectively unchanged on skew; none regresses.

`baseline.json` and `candidate.json` retain the raw JMH result arrays,
parameters, fork metadata, and per-iteration allocation measurements.
