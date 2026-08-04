# Polars comparison court

Full separate-process Polars timing receipt.

Polars runs eagerly in one Python process with a 14-thread pool at 1000000 rows. It is not invoked through JMH, and Python allocation is
not compared with JVM GC allocation. The `frame4s/Polars` column is a
cross-runtime ratio, not a JMH claim gate.

| Workload | Polars median | Range | frame4s JMH | frame4s/Polars | Ranked |
|---|---:|---:|---:|---:|---|
| `primitiveMaterializedProjection` | 0.007085 ms | 0.006963–0.007196 ms | 2.234073 ms | 315.31x | no |
| `fusedFilterProjectArithmetic` | 0.257377 ms | 0.235802–0.273667 ms | 3.164943 ms | 12.30x | yes |
| `groupedLowCardinalitySumOnly` | 1.713561 ms | 1.687569–1.845417 ms | 8.321949 ms | 4.86x | yes |
| `groupedLowCardinality` | 5.005163 ms | 4.900109–5.284742 ms | 10.743739 ms | 2.15x | yes |
| `joinOneToOne` | 8.474922 ms | 8.351385–9.073447 ms | 91.514516 ms | 10.80x | yes |
| `joinOneToMany` | 8.667488 ms | 7.702767–17.569537 ms | 93.241902 ms | 10.76x | yes |
| `joinSparse` | 3.834884 ms | 3.623532–4.223949 ms | 23.006152 ms | 6.00x | yes |
| `joinSkewed` | 0.574873 ms | 0.537785–0.660840 ms | 3.650145 ms | 6.35x | yes |
| `distinctLowCardinality` | 4.201200 ms | 3.960858–4.793530 ms | 14.280012 ms | 3.40x | yes |
| `semiJoinSparse` | 4.554326 ms | 4.136292–4.837603 ms | 20.710882 ms | 4.55x | yes |
| `antiJoinSparse` | 5.846635 ms | 5.150986–6.673108 ms | 30.806637 ms | 5.27x | yes |
| `unionAll` | 0.030892 ms | 0.027695–0.036053 ms | 10.708719 ms | 346.65x | yes |

## Validation and ranking limits

- `primitiveMaterializedProjection`: Polars answers a bare projection with a refcount clone while frame4s materializes owned output; retained as an unranked lower bound.
- `groupedLowCardinalitySumOnly`: Group order is not maintained, so output is invariant-validated.
- `groupedLowCardinality`: Different legal floating moment algorithms and unordered groups; row/schema and invariant validated, not raw-bit ranked.
- `joinOneToOne`: Polars drops the coalesced right key and does not maintain order; invariant validated. Both sides use monotonic keys, so a sorted fast path may be reachable and this is not a general-case join.
- `joinOneToMany`: Unordered duplicate-key output; invariant validated.
- `joinSparse`: Unordered output; invariant validated.
- `joinSkewed`: Unordered single-key fan-out; invariant validated.
- `distinctLowCardinality`: Unordered distinct; invariant validated.
- `semiJoinSparse`: Unordered semi join; invariant validated.
- `antiJoinSparse`: Unordered anti join; invariant validated.

Exact raw-bit checksums are required at or below 100000 rows. Larger tiers validate row count,
schema, and vectorized per-column invariants bound to output order,
because a Python row walk at those sizes costs more than the
measurement. Dropping to invariants is a stated limit, not a silent one.

1 workload(s) are retained as unranked lower bounds.

Oracle provenance for this receipt: columnar-candidate. A
`columnar-candidate` provenance means the frame4s validation row came from the
scale tier, where the semantic reference interpreter cannot execute, so output
agreement is with the candidate rather than with the oracle.

Raw per-sample aggregates are in `raw/timings.csv`; output validation is in
`validation.tsv`; runtime provenance is in `environment.properties`.
