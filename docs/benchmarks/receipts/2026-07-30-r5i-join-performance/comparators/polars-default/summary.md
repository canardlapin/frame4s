# Polars comparison court

Full separate-process Polars timing receipt.

Polars runs eagerly in one Python process with a 14-thread pool at 1000000 rows. It is not invoked through JMH, and Python allocation is
not compared with JVM GC allocation. The `frame4s/Polars` column is a
cross-runtime ratio, not a JMH claim gate.

| Workload | Polars median | Range | frame4s JMH | frame4s path | frame4s/Polars | Ranked |
|---|---:|---:|---:|---|---:|---|
| `primitiveMaterializedProjection` | 0.006735 ms | 0.006651–0.006812 ms | n/a | n/a | n/a | no |
| `fusedFilterProjectArithmetic` | 0.244817 ms | 0.235981–0.250768 ms | n/a | n/a | n/a | yes |
| `groupedLowCardinalitySumOnly` | 1.724022 ms | 1.697374–1.793633 ms | n/a | n/a | n/a | yes |
| `groupedLowCardinality` | 5.646745 ms | 5.506993–6.302890 ms | n/a | n/a | n/a | yes |
| `joinOneToOne` | 9.183659 ms | 8.649465–9.544044 ms | 29.902491 ms | detached-result-construction | 3.26x | yes |
| `joinOneToMany` | 8.597083 ms | 8.088345–8.964012 ms | 30.603942 ms | detached-result-construction | 3.56x | yes |
| `joinSparse` | 4.057808 ms | 4.019146–4.418134 ms | 15.635404 ms | detached-result-construction | 3.85x | yes |
| `joinSkewed` | 0.496824 ms | 0.477692–0.526627 ms | 2.077880 ms | detached-result-construction | 4.18x | yes |
| `distinctLowCardinality` | 3.849876 ms | 3.780484–4.863237 ms | n/a | n/a | n/a | yes |
| `semiJoinSparse` | 4.566862 ms | 4.483654–6.704447 ms | 16.496914 ms | detached-result-construction | 3.61x | yes |
| `antiJoinSparse` | 5.270776 ms | 5.037601–6.071537 ms | 17.530848 ms | detached-result-construction | 3.33x | yes |
| `unionAll` | 0.027538 ms | 0.025635–0.038792 ms | n/a | n/a | n/a | yes |

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
