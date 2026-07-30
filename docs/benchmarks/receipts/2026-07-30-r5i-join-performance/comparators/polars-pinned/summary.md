# Polars comparison court

Full separate-process Polars timing receipt.

Polars runs eagerly in one Python process with a 1-thread pool at 1000000 rows. It is not invoked through JMH, and Python allocation is
not compared with JVM GC allocation. The `frame4s/Polars` column is a
cross-runtime ratio, not a JMH claim gate.

| Workload | Polars median | Range | frame4s JMH | frame4s path | frame4s/Polars | Ranked |
|---|---:|---:|---:|---|---:|---|
| `primitiveMaterializedProjection` | 0.006718 ms | 0.006691–0.006820 ms | n/a | n/a | n/a | no |
| `fusedFilterProjectArithmetic` | 0.167797 ms | 0.161394–0.172364 ms | n/a | n/a | n/a | yes |
| `groupedLowCardinalitySumOnly` | 6.367420 ms | 6.303885–6.400378 ms | n/a | n/a | n/a | yes |
| `groupedLowCardinality` | 23.364831 ms | 23.195503–23.520299 ms | n/a | n/a | n/a | yes |
| `joinOneToOne` | 48.082198 ms | 46.591031–49.199823 ms | 29.902491 ms | detached-result-construction | 0.62x | yes |
| `joinOneToMany` | 42.781385 ms | 41.932073–44.440526 ms | 30.603942 ms | detached-result-construction | 0.72x | yes |
| `joinSparse` | 11.494747 ms | 11.436714–11.752207 ms | 15.635404 ms | detached-result-construction | 1.36x | yes |
| `joinSkewed` | 1.426363 ms | 1.407337–1.448065 ms | 2.077880 ms | detached-result-construction | 1.46x | yes |
| `distinctLowCardinality` | 18.521232 ms | 18.164612–18.892206 ms | n/a | n/a | n/a | yes |
| `semiJoinSparse` | 12.058964 ms | 11.874026–12.332553 ms | 16.496914 ms | detached-result-construction | 1.37x | yes |
| `antiJoinSparse` | 14.279345 ms | 13.946715–14.669548 ms | 17.530848 ms | detached-result-construction | 1.23x | yes |
| `unionAll` | 0.009243 ms | 0.007723–0.009845 ms | n/a | n/a | n/a | yes |

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
