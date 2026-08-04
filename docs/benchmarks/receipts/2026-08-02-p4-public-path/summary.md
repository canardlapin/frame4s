# P4 public-path replacement court

This court replaces the invalidated 2026-08-01 receipt. It measures the public
`FrameRuntime.collectWithReceipt` path against package-internal engine collect
on identical plans and sources after the streaming, engine-policy, and
columnar-subsystem changes. The runner uses five alternating paired warmup
iterations and fifteen alternating paired measured iterations at 1,000,000
rows, and reports the median wall-clock time for each endpoint. Alternating
which endpoint runs first in each pair prevents either endpoint from always
receiving the later, warmer part of the process lifetime. This is a
same-process public-versus-internal comparison, not a JMH result.

Every direct-engine and public iteration asserts typed engine identity. Filter,
join, and grouping must return `EngineId.Columnar` with no fallback. The bare
scan must return `EngineId.Reference` with its fallback reason. A silent
decline fails the run.

| Workload | Engine collect | Public collect | Public delta |
|---|---:|---:|---:|
| filter (`FilterSelection`) | 15.03 ms | 15.14 ms | +0.7% |
| one-to-one join, shuffled keys (`HashJoin`) | 68.02 ms | 68.09 ms | +0.1% |
| high-cardinality count, shuffled keys (`HashAggregate`) | 36.90 ms | 37.25 ms | +1.0% |
| bare scan (reference fallback) | — | 59.66 ms | single execution |

This run supports the narrow claim that the admitted 1M-row filter, shuffled
join, and shuffled high-cardinality grouping public paths are within 1% of
direct engine collect on this machine and methodology. The public path
transfers already-owned columnar batches into the scoped table instead of
copying them a second time; reference-stream batches retain the defensive
detach step. The court does not establish cross-machine performance,
allocation behavior, or JMH comparability.

`source-files.list` declares the exact 18-file source set, and
`source-files.sha256` binds it to the measured candidate.
`manifest-verifier.txt` records the passing exact-set verification plus tests
that mismatch, missing, and unexpected paths fail. The source manifest was
verified again after the court.

See [verification.md](verification.md) for the functional gates that preceded
the measurement and [metrics.tsv](metrics.tsv) for the raw samples summary.
