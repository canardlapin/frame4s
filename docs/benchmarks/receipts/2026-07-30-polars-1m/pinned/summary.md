# Polars comparison court

Full separate-process Polars timing receipt.

Polars runs eagerly in one Python process with a 1-thread pool at 1000000 rows. It is not invoked through JMH, and Python allocation is
not compared with JVM GC allocation. The `frame4s/Polars` column is a
cross-runtime ratio, not a JMH claim gate.

| Workload | Polars median | Range | frame4s JMH | frame4s/Polars | Ranked |
|---|---:|---:|---:|---:|---|
| `primitiveMaterializedProjection` | 0.007445 ms | 0.007286–0.007886 ms | 2.234073 ms | 300.10x | no |
| `fusedFilterProjectArithmetic` | 0.198126 ms | 0.175236–0.354299 ms | 3.164943 ms | 15.97x | yes |
| `groupedLowCardinalitySumOnly` | 6.470002 ms | 6.387331–8.920398 ms | 8.321949 ms | 1.29x | yes |
| `groupedLowCardinality` | 24.743380 ms | 23.484844–29.837320 ms | 10.743739 ms | 0.43x | yes |
| `joinOneToOne` | 47.141401 ms | 41.356740–51.854693 ms | 91.514516 ms | 1.94x | yes |
| `joinOneToMany` | 40.190875 ms | 38.792135–43.426078 ms | 93.241902 ms | 2.32x | yes |
| `joinSparse` | 11.417129 ms | 11.104124–11.504526 ms | 23.006152 ms | 2.02x | yes |
| `joinSkewed` | 1.395155 ms | 1.384941–1.413284 ms | 3.650145 ms | 2.62x | yes |
| `distinctLowCardinality` | 18.578906 ms | 17.587839–19.202221 ms | 14.280012 ms | 0.77x | yes |
| `semiJoinSparse` | 12.295306 ms | 12.153232–12.436996 ms | 20.710882 ms | 1.68x | yes |
| `antiJoinSparse` | 14.067389 ms | 13.878461–22.487367 ms | 30.806637 ms | 2.19x | yes |
| `unionAll` | 0.005977 ms | 0.005612–0.006503 ms | 10.708719 ms | 1791.63x | yes |

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
