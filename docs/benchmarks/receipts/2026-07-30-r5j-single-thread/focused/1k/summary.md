# Focused 1K regression court

This comparison runs the baseline at commit `1293232` and the candidate with
the same six-method JMH command, 1 GiB heap, fork count, warmup, and measurement
settings. The table uses the median of the five raw measurement iterations.

| Workload | Baseline | Candidate | Speedup | Candidate allocation |
|---|---:|---:|---:|---:|
| anti sparse | 0.004294 ms | 0.003343 ms | 1.284x | 0.819x |
| one-to-many | 0.011100 ms | 0.010166 ms | 1.092x | 0.889x |
| one-to-one | 0.008669 ms | 0.006999 ms | 1.239x | 0.763x |
| skewed | 0.012433 ms | 0.011707 ms | 1.062x | 0.890x |
| sparse | 0.004173 ms | 0.003234 ms | 1.290x | 0.914x |
| semi sparse | 0.004132 ms | 0.003042 ms | 1.358x | 0.930x |

Every measured shape improves by 6--26%. The candidate therefore does not buy
large-row throughput by regressing the 1K latency tier.

`baseline.json` and `candidate.json` retain the raw JMH result arrays,
parameters, fork metadata, and per-iteration allocation measurements.
