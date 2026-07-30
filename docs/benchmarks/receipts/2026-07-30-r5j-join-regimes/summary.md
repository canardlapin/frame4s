# Join size and key-order regime court

All four timing paths use identical deterministic key/value multisets.
Ratios below 1.00 favor frame4s. Polars is reported both pinned to one
thread and at its default pool.

| Rows | Key order | frame4s | pandas | Polars 1t | Polars default | f4s/pandas | f4s/Polars 1t | f4s/Polars default |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 1000 | both-shuffled | 0.034 ms | 0.264 ms | 0.061 ms | 0.230 ms | 0.13x | 0.56x | 0.15x |
| 1000 | right-shuffled | 0.015 ms | 0.256 ms | 0.060 ms | 0.246 ms | 0.06x | 0.25x | 0.06x |
| 1000 | sorted | 0.014 ms | 0.219 ms | 0.061 ms | 0.252 ms | 0.07x | 0.23x | 0.06x |
| 4000 | both-shuffled | 0.043 ms | 0.349 ms | 0.110 ms | 0.266 ms | 0.12x | 0.39x | 0.16x |
| 4000 | right-shuffled | 0.073 ms | 0.313 ms | 0.110 ms | 0.259 ms | 0.23x | 0.66x | 0.28x |
| 4000 | sorted | 0.047 ms | 0.237 ms | 0.108 ms | 0.266 ms | 0.20x | 0.43x | 0.18x |
| 16000 | both-shuffled | 0.163 ms | 0.917 ms | 0.308 ms | 0.358 ms | 0.18x | 0.53x | 0.46x |
| 16000 | right-shuffled | 0.171 ms | 0.454 ms | 0.314 ms | 0.369 ms | 0.38x | 0.54x | 0.46x |
| 16000 | sorted | 0.355 ms | 0.263 ms | 0.301 ms | 0.383 ms | 1.35x | 1.18x | 0.93x |
| 64000 | both-shuffled | 1.267 ms | 1.405 ms | 1.235 ms | 1.361 ms | 0.90x | 1.03x | 0.93x |
| 64000 | right-shuffled | 1.225 ms | 1.474 ms | 1.229 ms | 1.082 ms | 0.83x | 1.00x | 1.13x |
| 64000 | sorted | 1.818 ms | 0.387 ms | 1.184 ms | 1.006 ms | 4.69x | 1.54x | 1.81x |
| 256000 | both-shuffled | 5.959 ms | 5.897 ms | 8.541 ms | 2.926 ms | 1.01x | 0.70x | 2.04x |
| 256000 | right-shuffled | 6.177 ms | 5.240 ms | 8.279 ms | 2.959 ms | 1.18x | 0.75x | 2.09x |
| 256000 | sorted | 5.790 ms | 0.675 ms | 8.160 ms | 2.876 ms | 8.58x | 0.71x | 2.01x |
| 1000000 | both-shuffled | 29.327 ms | 44.666 ms | 52.830 ms | 10.343 ms | 0.66x | 0.56x | 2.84x |
| 1000000 | right-shuffled | 30.116 ms | 29.484 ms | 50.885 ms | 9.510 ms | 1.02x | 0.59x | 3.17x |
| 1000000 | sorted | 29.784 ms | 2.080 ms | 50.422 ms | 9.261 ms | 14.32x | 0.59x | 3.22x |
| 4000000 | both-shuffled | 183.309 ms | 242.470 ms | 267.141 ms | 56.327 ms | 0.76x | 0.69x | 3.25x |
| 4000000 | right-shuffled | 185.287 ms | 231.200 ms | 358.418 ms | 59.461 ms | 0.80x | 0.52x | 3.12x |
| 4000000 | sorted | 190.000 ms | 8.442 ms | 281.797 ms | 63.695 ms | 22.51x | 0.67x | 2.98x |

## Relative regimes

These descriptive ranges use a ±5% tie band. They locate observed
winner changes; they do not admit an optimization by themselves.

- pandas / both-shuffled / 1000–64000 rows: frame4s-faster (0.12–0.90x).
- pandas / both-shuffled / 256000 rows: within-5-percent (1.01–1.01x).
- pandas / both-shuffled / 1000000–4000000 rows: frame4s-faster (0.66–0.76x).
- pandas / right-shuffled / 1000–64000 rows: frame4s-faster (0.06–0.83x).
- pandas / right-shuffled / 256000 rows: comparator-faster (1.18–1.18x).
- pandas / right-shuffled / 1000000 rows: within-5-percent (1.02–1.02x).
- pandas / right-shuffled / 4000000 rows: frame4s-faster (0.80–0.80x).
- pandas / sorted / 1000–4000 rows: frame4s-faster (0.07–0.20x).
- pandas / sorted / 16000–4000000 rows: comparator-faster (1.35–22.51x).
- polars-pinned / both-shuffled / 1000–16000 rows: frame4s-faster (0.39–0.56x).
- polars-pinned / both-shuffled / 64000 rows: within-5-percent (1.03–1.03x).
- polars-pinned / both-shuffled / 256000–4000000 rows: frame4s-faster (0.56–0.70x).
- polars-pinned / right-shuffled / 1000–16000 rows: frame4s-faster (0.25–0.66x).
- polars-pinned / right-shuffled / 64000 rows: within-5-percent (1.00–1.00x).
- polars-pinned / right-shuffled / 256000–4000000 rows: frame4s-faster (0.52–0.75x).
- polars-pinned / sorted / 1000–4000 rows: frame4s-faster (0.23–0.43x).
- polars-pinned / sorted / 16000–64000 rows: comparator-faster (1.18–1.54x).
- polars-pinned / sorted / 256000–4000000 rows: frame4s-faster (0.59–0.71x).
- polars-default / both-shuffled / 1000–64000 rows: frame4s-faster (0.15–0.93x).
- polars-default / both-shuffled / 256000–4000000 rows: comparator-faster (2.04–3.25x).
- polars-default / right-shuffled / 1000–16000 rows: frame4s-faster (0.06–0.46x).
- polars-default / right-shuffled / 64000–4000000 rows: comparator-faster (1.13–3.17x).
- polars-default / sorted / 1000–16000 rows: frame4s-faster (0.06–0.93x).
- polars-default / sorted / 64000–4000000 rows: comparator-faster (1.81–3.22x).

## Candidate crossover intervals

Adjacent intervals are listed when per-row cost rises by at least 25%.

- frame4s / both-shuffled: 16000–64000 rows (1.94x per-row cost).
- frame4s / both-shuffled: 256000–1000000 rows (1.26x per-row cost).
- frame4s / both-shuffled: 1000000–4000000 rows (1.56x per-row cost).
- frame4s / right-shuffled: 16000–64000 rows (1.79x per-row cost).
- frame4s / right-shuffled: 64000–256000 rows (1.26x per-row cost).
- frame4s / right-shuffled: 1000000–4000000 rows (1.54x per-row cost).
- frame4s / sorted: 4000–16000 rows (1.90x per-row cost).
- frame4s / sorted: 16000–64000 rows (1.28x per-row cost).
- frame4s / sorted: 256000–1000000 rows (1.32x per-row cost).
- frame4s / sorted: 1000000–4000000 rows (1.59x per-row cost).
- pandas / both-shuffled: 256000–1000000 rows (1.94x per-row cost).
- pandas / both-shuffled: 1000000–4000000 rows (1.36x per-row cost).
- pandas / right-shuffled: 256000–1000000 rows (1.44x per-row cost).
- pandas / right-shuffled: 1000000–4000000 rows (1.96x per-row cost).
- polars-pinned / both-shuffled: 64000–256000 rows (1.73x per-row cost).
- polars-pinned / both-shuffled: 256000–1000000 rows (1.58x per-row cost).
- polars-pinned / both-shuffled: 1000000–4000000 rows (1.26x per-row cost).
- polars-pinned / right-shuffled: 64000–256000 rows (1.68x per-row cost).
- polars-pinned / right-shuffled: 256000–1000000 rows (1.57x per-row cost).
- polars-pinned / right-shuffled: 1000000–4000000 rows (1.76x per-row cost).
- polars-pinned / sorted: 64000–256000 rows (1.72x per-row cost).
- polars-pinned / sorted: 256000–1000000 rows (1.58x per-row cost).
- polars-pinned / sorted: 1000000–4000000 rows (1.40x per-row cost).
- polars-default / both-shuffled: 1000000–4000000 rows (1.36x per-row cost).
- polars-default / right-shuffled: 1000000–4000000 rows (1.56x per-row cost).
- polars-default / sorted: 1000000–4000000 rows (1.72x per-row cost).

`combined.tsv` contains the exact ratios. Component receipts retain
individual samples, allocation, stage attribution, validation invariants,
versions, thread counts, and deterministic permutation fingerprints.
