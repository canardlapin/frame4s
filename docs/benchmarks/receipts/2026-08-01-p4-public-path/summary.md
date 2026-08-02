# P4 public-path court

Measures the public `FrameRuntime.collectWithReceipt` path against the
package-internal engine collect on the same plans and sources, per Phase 4
of the performance parity plan and ADR-0006. Wall-clock process-level
timing (3 warmup, 7 measured iterations, median reported), the same
methodology as the pandas and Polars courts — not JMH. Produced by
`frame4s.benchmarks.PublicPathCourtRunner` at 1,000,000 rows on the
hardware in `environment.properties`.

Every public iteration asserts the engine receipt, so this court fails
rather than silently measuring the reference interpreter: the filter and
join workloads must report `backend=columnar` with no fallback, and the
bare-scan workload must report `backend=reference` with its stated
reason.

| Workload | Engine collect | Public collect | Public overhead |
|---|---:|---:|---:|
| filter (FilterSelection) | 18.69 ms | 19.74 ms | 5.7% |
| one-to-one join, shuffled keys (HashJoin) | 70.71 ms | 73.50 ms | 3.9% |
| bare scan (reference fallback) | — | 65.03 ms | single execution |

The public materializing path therefore delivers the optimized engine's
performance within single-digit overhead — the overhead is the runtime's
slice-and-retain ownership pass and `Table` construction — and the
fallback path executes the plan exactly once in-engine instead of the
pre-ADR-0006 discard-and-rerun.

Wall-clock numbers here are not comparable to the JMH receipts
(allocation-profiler-free, different iteration discipline); they answer
only the public-versus-internal question on identical terms.
