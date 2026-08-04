# Join size and key-order regime court

All four timing paths use identical deterministic key/value multisets.
Ratios below 1.00 favor frame4s. Polars is reported both pinned to one
thread and at its default pool.

| Rows | Key order | frame4s | pandas | Polars 1t | Polars default | f4s/pandas | f4s/Polars 1t | f4s/Polars default |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 1000 | both-shuffled | 0.007 ms | 0.228 ms | 0.057 ms | 0.212 ms | 0.03x | 0.12x | 0.03x |
| 1000 | right-shuffled | 0.007 ms | 0.231 ms | 0.057 ms | 0.218 ms | 0.03x | 0.12x | 0.03x |
| 1000 | sorted | 0.007 ms | 0.203 ms | 0.058 ms | 0.212 ms | 0.03x | 0.12x | 0.03x |
| 4000 | both-shuffled | 0.024 ms | 0.253 ms | 0.105 ms | 0.263 ms | 0.09x | 0.23x | 0.09x |
| 4000 | right-shuffled | 0.024 ms | 0.258 ms | 0.106 ms | 0.256 ms | 0.09x | 0.23x | 0.09x |
| 4000 | sorted | 0.024 ms | 0.208 ms | 0.105 ms | 0.265 ms | 0.12x | 0.23x | 0.09x |
| 16000 | both-shuffled | 0.095 ms | 0.393 ms | 0.298 ms | 0.340 ms | 0.24x | 0.32x | 0.28x |
| 16000 | right-shuffled | 0.097 ms | 0.389 ms | 0.297 ms | 0.337 ms | 0.25x | 0.33x | 0.29x |
| 16000 | sorted | 0.098 ms | 0.229 ms | 0.293 ms | 0.340 ms | 0.43x | 0.33x | 0.29x |
| 64000 | both-shuffled | 0.625 ms | 1.077 ms | 1.137 ms | 0.768 ms | 0.58x | 0.55x | 0.81x |
| 64000 | right-shuffled | 0.628 ms | 1.113 ms | 1.136 ms | 0.778 ms | 0.56x | 0.55x | 0.81x |
| 64000 | sorted | 0.586 ms | 0.313 ms | 1.097 ms | 0.767 ms | 1.87x | 0.53x | 0.76x |
| 256000 | both-shuffled | 2.290 ms | 4.576 ms | 6.008 ms | 2.094 ms | 0.50x | 0.38x | 1.09x |
| 256000 | right-shuffled | 2.251 ms | 4.358 ms | 6.034 ms | 2.103 ms | 0.52x | 0.37x | 1.07x |
| 256000 | sorted | 2.362 ms | 0.649 ms | 5.795 ms | 2.010 ms | 3.64x | 0.41x | 1.17x |
| 1000000 | both-shuffled | 9.846 ms | 27.653 ms | 42.144 ms | 8.351 ms | 0.36x | 0.23x | 1.18x |
| 1000000 | right-shuffled | 9.819 ms | 23.320 ms | 40.563 ms | 8.246 ms | 0.42x | 0.24x | 1.19x |
| 1000000 | sorted | 9.265 ms | 1.963 ms | 41.576 ms | 7.844 ms | 4.72x | 0.22x | 1.18x |
| 4000000 | both-shuffled | 68.388 ms | 217.476 ms | 219.440 ms | 54.082 ms | 0.31x | 0.31x | 1.26x |
| 4000000 | right-shuffled | 67.283 ms | 176.820 ms | 223.386 ms | 54.858 ms | 0.38x | 0.30x | 1.23x |
| 4000000 | sorted | 37.116 ms | 7.557 ms | 208.286 ms | 48.748 ms | 4.91x | 0.18x | 0.76x |

## Relative regimes

These descriptive ranges use a ±5% tie band. They locate observed
winner changes; they do not admit an optimization by themselves.

- pandas / both-shuffled / 1000–4000000 rows: frame4s-faster (0.03–0.58x).
- pandas / right-shuffled / 1000–4000000 rows: frame4s-faster (0.03–0.56x).
- pandas / sorted / 1000–16000 rows: frame4s-faster (0.03–0.43x).
- pandas / sorted / 64000–4000000 rows: comparator-faster (1.87–4.91x).
- polars-pinned / both-shuffled / 1000–4000000 rows: frame4s-faster (0.12–0.55x).
- polars-pinned / right-shuffled / 1000–4000000 rows: frame4s-faster (0.12–0.55x).
- polars-pinned / sorted / 1000–4000000 rows: frame4s-faster (0.12–0.53x).
- polars-default / both-shuffled / 1000–64000 rows: frame4s-faster (0.03–0.81x).
- polars-default / both-shuffled / 256000–4000000 rows: comparator-faster (1.09–1.26x).
- polars-default / right-shuffled / 1000–64000 rows: frame4s-faster (0.03–0.81x).
- polars-default / right-shuffled / 256000–4000000 rows: comparator-faster (1.07–1.23x).
- polars-default / sorted / 1000–64000 rows: frame4s-faster (0.03–0.76x).
- polars-default / sorted / 256000–1000000 rows: comparator-faster (1.17–1.18x).
- polars-default / sorted / 4000000 rows: frame4s-faster (0.76–0.76x).

## Candidate crossover intervals

Adjacent intervals are listed when per-row cost rises by at least 25%.

- frame4s / both-shuffled: 16000–64000 rows (1.65x per-row cost).
- frame4s / both-shuffled: 1000000–4000000 rows (1.74x per-row cost).
- frame4s / right-shuffled: 16000–64000 rows (1.62x per-row cost).
- frame4s / right-shuffled: 1000000–4000000 rows (1.71x per-row cost).
- frame4s / sorted: 16000–64000 rows (1.50x per-row cost).
- pandas / both-shuffled: 256000–1000000 rows (1.55x per-row cost).
- pandas / both-shuffled: 1000000–4000000 rows (1.97x per-row cost).
- pandas / right-shuffled: 256000–1000000 rows (1.37x per-row cost).
- pandas / right-shuffled: 1000000–4000000 rows (1.90x per-row cost).
- polars-pinned / both-shuffled: 64000–256000 rows (1.32x per-row cost).
- polars-pinned / both-shuffled: 256000–1000000 rows (1.80x per-row cost).
- polars-pinned / both-shuffled: 1000000–4000000 rows (1.30x per-row cost).
- polars-pinned / right-shuffled: 64000–256000 rows (1.33x per-row cost).
- polars-pinned / right-shuffled: 256000–1000000 rows (1.72x per-row cost).
- polars-pinned / right-shuffled: 1000000–4000000 rows (1.38x per-row cost).
- polars-pinned / sorted: 64000–256000 rows (1.32x per-row cost).
- polars-pinned / sorted: 256000–1000000 rows (1.84x per-row cost).
- polars-pinned / sorted: 1000000–4000000 rows (1.25x per-row cost).
- polars-default / both-shuffled: 1000000–4000000 rows (1.62x per-row cost).
- polars-default / right-shuffled: 1000000–4000000 rows (1.66x per-row cost).
- polars-default / sorted: 1000000–4000000 rows (1.55x per-row cost).

`combined.tsv` contains the exact ratios. Component receipts retain
individual samples, allocation, stage attribution, validation invariants,
versions, thread counts, and deterministic permutation fingerprints.
