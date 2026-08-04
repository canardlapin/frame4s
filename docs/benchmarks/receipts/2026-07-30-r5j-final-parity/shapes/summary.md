# Sparse and existential join regime court

frame4s and Polars use identical deterministic fixtures. Ratios at or
below 1.05 pass the pinned single-thread target.

| Rows | Workload | Order | frame4s | Polars 1t | Polars default | f4s/Polars 1t | f4s/Polars default | Allocation | Gates |
|---:|---|---|---:|---:|---:|---:|---:|---:|---|
| 256000 | anti-sparse | both-shuffled | 0.565 ms | 3.663 ms | 1.013 ms | 0.15x | 0.56x | 4.94 MB | 1t-pass, default-pass |
| 256000 | anti-sparse | sorted | 1.640 ms | 3.579 ms | 0.894 ms | 0.46x | 1.84x | 4.41 MB | 1t-pass, default-pass |
| 256000 | join-skewed | both-shuffled | 0.178 ms | 0.394 ms | 0.235 ms | 0.45x | 0.75x | 3.13 MB | 1t-pass, default-pass |
| 256000 | join-skewed | sorted | 0.175 ms | 0.394 ms | 0.223 ms | 0.44x | 0.78x | 3.13 MB | 1t-pass, default-pass |
| 256000 | join-sparse | both-shuffled | 0.976 ms | 3.147 ms | 0.678 ms | 0.31x | 1.44x | 4.12 MB | 1t-pass, default-pass |
| 256000 | join-sparse | sorted | 1.537 ms | 3.022 ms | 0.647 ms | 0.51x | 2.37x | 3.59 MB | 1t-pass, default-miss |
| 256000 | semi-sparse | both-shuffled | 0.955 ms | 3.315 ms | 0.845 ms | 0.29x | 1.13x | 4.02 MB | 1t-pass, default-pass |
| 256000 | semi-sparse | sorted | 1.574 ms | 3.071 ms | 0.688 ms | 0.51x | 2.29x | 3.49 MB | 1t-pass, default-miss |
| 1000000 | anti-sparse | both-shuffled | 2.662 ms | 13.889 ms | 5.177 ms | 0.19x | 0.51x | 19.32 MB | 1t-pass, default-pass |
| 1000000 | anti-sparse | sorted | 6.449 ms | 13.987 ms | 4.991 ms | 0.46x | 1.29x | 17.21 MB | 1t-pass, default-pass |
| 1000000 | join-skewed | both-shuffled | 0.603 ms | 1.384 ms | 0.433 ms | 0.44x | 1.39x | 12.07 MB | 1t-pass, default-pass |
| 1000000 | join-skewed | sorted | 0.519 ms | 1.383 ms | 0.354 ms | 0.37x | 1.47x | 12.07 MB | 1t-pass, default-pass |
| 1000000 | join-sparse | both-shuffled | 4.198 ms | 11.697 ms | 3.762 ms | 0.36x | 1.12x | 16.13 MB | 1t-pass, default-pass |
| 1000000 | join-sparse | sorted | 5.975 ms | 10.974 ms | 3.713 ms | 0.54x | 1.61x | 14.01 MB | 1t-pass, default-pass |
| 1000000 | semi-sparse | both-shuffled | 4.119 ms | 13.067 ms | 4.475 ms | 0.32x | 0.92x | 15.73 MB | 1t-pass, default-pass |
| 1000000 | semi-sparse | sorted | 6.024 ms | 11.844 ms | 4.275 ms | 0.51x | 1.41x | 13.61 MB | 1t-pass, default-pass |
| 4000000 | anti-sparse | both-shuffled | 10.914 ms | 65.544 ms | 17.517 ms | 0.17x | 0.62x | 77.22 MB | 1t-pass, default-pass |
| 4000000 | anti-sparse | sorted | 25.920 ms | 65.458 ms | 17.331 ms | 0.40x | 1.50x | 68.81 MB | 1t-pass, default-pass |
| 4000000 | join-skewed | both-shuffled | 2.394 ms | 5.321 ms | 0.991 ms | 0.45x | 2.42x | 48.07 MB | 1t-pass, default-miss |
| 4000000 | join-skewed | sorted | 2.419 ms | 5.322 ms | 0.678 ms | 0.45x | 3.57x | 48.07 MB | 1t-pass, default-miss |
| 4000000 | join-sparse | both-shuffled | 16.716 ms | 53.893 ms | 14.006 ms | 0.31x | 1.19x | 64.43 MB | 1t-pass, default-pass |
| 4000000 | join-sparse | sorted | 23.706 ms | 50.895 ms | 13.990 ms | 0.47x | 1.69x | 56.01 MB | 1t-pass, default-pass |
| 4000000 | semi-sparse | both-shuffled | 16.785 ms | 55.292 ms | 15.318 ms | 0.30x | 1.10x | 62.82 MB | 1t-pass, default-pass |
| 4000000 | semi-sparse | sorted | 24.180 ms | 50.629 ms | 14.767 ms | 0.48x | 1.64x | 54.41 MB | 1t-pass, default-pass |

Every measured regime passes.
4 measured regime(s) remain above 2.0x default Polars.

Component receipts retain raw samples, versions, thread counts, exact
frame4s checksums, comparator invariants, and matching fixture hashes.
