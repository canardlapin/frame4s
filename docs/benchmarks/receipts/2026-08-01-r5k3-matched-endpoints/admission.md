# R5k.3 matched endpoints, post typed gather

This court re-runs the [R5k.1 matched-endpoint protocol](../2026-07-30-r5k1-matched-endpoints/admission.md)
after the [typed gather admission](../2026-08-01-r5k3-typed-gather/admission.md),
restating the cross-runtime construction ratios on the corrected
deep-materialized endpoint. Same protocol: three interleaved process-level
rounds with rotated sequence positions, identical four-column stable-left
one-to-one joins at 1M and 4M rows across sorted, right-shuffled, and
both-shuffled key orders, exact ordered SHA-256 output digests and
full-column sums matched across frame4s, pandas 3.0.1, pinned Polars
1.43.1, and default-threaded (14) Polars 1.43.1.

One environment note travels with this receipt. `polars==1.43.1` was
yanked from PyPI after R5k.1, so uv can no longer resolve it. The court
script gained a `FRAME4S_POLARS_PYTHON` override naming an interpreter
with the exact pinned versions installed via pip, which accepts yanked
releases under an exact pin. Each component's `environment.properties`
records the imported `backend.version=1.43.1`, so the substitution is
auditable, and both Polars variants ran from that interpreter in every
round.

## Corrected construction ratios, deep-materialized, round-paired medians

| Rows | Order | f4s/pandas | f4s/Polars 1t | f4s/Polars default |
|---:|---|---:|---:|---:|
| 1M | sorted | 20.51x | **0.77x** | 3.48x |
| 1M | right-shuffled | 2.27x | 1.05x | 3.15x |
| 1M | both-shuffled | 1.73x | **0.99x** | 3.28x |
| 4M | sorted | 22.96x | **0.68x** | 3.15x |
| 4M | right-shuffled | 2.20x | 1.35x | 4.92x |
| 4M | both-shuffled | 1.68x | 1.40x | 5.30x |

Against R5k.1's frozen ratios, sorted deep construction moves from 42.62x
to 20.51x pandas at 1M and 47.37x to 22.96x at 4M; from 1.82x to 0.77x
and 1.40x to 0.68x pinned Polars, so frame4s now **beats single-threaded
Polars on the corrected endpoint** for sorted inputs and reaches parity at
1M both-shuffled; and from 8.21x to 3.48x and 6.28x to 3.15x default
Polars. frame4s sorted deep materialization fell from 140.90 to 40.51 ms
at 1M and from 654.68 to 183.48 ms at 4M with allocation at 56.51 and
194.02 MB.

## What remains

- **pandas sorted is a merge comparison, not a hash comparison.** pandas
  detects the monotonic key and answers in 1.98 ms at 1M; the 20.51x is
  the cost of producing owned output against a fused monotonic merge and
  is reported separately, as in R5j.
- **Shuffled deep output remains 1.7--2.4x pandas.** pandas' hash join
  writes its physical result faster than the frame4s gather; this is the
  next single-threaded target.
- **Default Polars remains 3.15--5.81x ahead**, consistent with its
  4--5x threading gain on this host. Closing it requires parallel
  materialization, not further single-threaded gather work.

`combined.tsv` holds every round median, `frame4s-allocation.tsv` and
`frame4s-diagnostics.tsv` the frame4s-only endpoints, `process-order.tsv`
the interleaving proof, and `validation.tsv` the matched digests. Round
directories retain raw samples and per-component environments.
`source-files.sha256` binds the receipt to the measured interpreter,
storage builders, runner, and court scripts.
