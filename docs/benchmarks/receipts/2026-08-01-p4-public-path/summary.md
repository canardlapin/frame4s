# P4 public-path court

> **Invalidated 2026-08-02.** The committed source manifest does not identify
> the source state that produced these metrics. The numbers below are retained
> as historical output, but they are not admissible performance evidence and
> must not support a release or parity claim. See [STATUS.md](STATUS.md).

Measures the public `FrameRuntime.collectWithReceipt` path against the
package-internal engine collect on the same plans and sources, per Phase 4
of the performance parity plan and ADR-0006. Wall-clock process-level
timing (3 warmup, 7 measured iterations, median reported), the same
methodology as the pandas and Polars courts — not JMH. Produced by
`frame4s.benchmarks.PublicPathCourtRunner` at 1,000,000 rows on the
hardware in `environment.properties`.

The historical runner asserted the engine receipt, so the measured process
would have failed
rather than silently measuring the reference interpreter: the filter and
join workloads required the columnar engine with no fallback, and the bare-scan
workload required reference fallback. Engine identity is typed in the current
API; the older receipt predates that contract.

| Workload | Engine collect | Public collect | Public overhead |
|---|---:|---:|---:|
| filter (FilterSelection) | 18.69 ms | 19.74 ms | 5.7% |
| one-to-one join, shuffled keys (HashJoin) | 70.71 ms | 73.50 ms | 3.9% |
| bare scan (reference fallback) | — | 65.03 ms | single execution |

These historical timings cannot establish public-path overhead because their
source provenance is invalid.

Wall-clock numbers here are not comparable to the JMH receipts
(allocation-profiler-free, different iteration discipline); they answer
only the public-versus-internal question on identical terms.
