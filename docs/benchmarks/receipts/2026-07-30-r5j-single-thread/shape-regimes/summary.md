# Sparse and existential join regime court

frame4s and Polars use identical deterministic fixtures. Ratios at or
below 1.05 pass the pinned single-thread target.

| Rows | Workload | Order | frame4s | Polars 1t | Ratio | Allocation | Gate |
|---:|---|---|---:|---:|---:|---:|---|
| 256000 | anti-sparse | both-shuffled | 3.357 ms | 3.836 ms | 0.88x | 4.93 MB | pass |
| 256000 | anti-sparse | sorted | 3.289 ms | 3.771 ms | 0.87x | 4.93 MB | pass |
| 256000 | join-skewed | both-shuffled | 0.326 ms | 0.553 ms | 0.59x | 3.12 MB | pass |
| 256000 | join-skewed | sorted | 0.360 ms | 0.612 ms | 0.59x | 3.12 MB | pass |
| 256000 | join-sparse | both-shuffled | 3.482 ms | 3.406 ms | 1.02x | 4.11 MB | pass |
| 256000 | join-sparse | sorted | 3.334 ms | 3.238 ms | 1.03x | 4.11 MB | pass |
| 256000 | semi-sparse | both-shuffled | 3.315 ms | 3.510 ms | 0.94x | 4.01 MB | pass |
| 256000 | semi-sparse | sorted | 3.373 ms | 3.958 ms | 0.85x | 4.01 MB | pass |
| 1000000 | anti-sparse | both-shuffled | 14.290 ms | 14.962 ms | 0.96x | 19.30 MB | pass |
| 1000000 | anti-sparse | sorted | 13.360 ms | 14.498 ms | 0.92x | 19.30 MB | pass |
| 1000000 | join-skewed | both-shuffled | 2.143 ms | 1.536 ms | 1.40x | 12.05 MB | miss |
| 1000000 | join-skewed | sorted | 1.269 ms | 1.456 ms | 0.87x | 12.05 MB | pass |
| 1000000 | join-sparse | both-shuffled | 15.259 ms | 12.457 ms | 1.22x | 16.10 MB | miss |
| 1000000 | join-sparse | sorted | 13.182 ms | 11.747 ms | 1.12x | 16.10 MB | miss |
| 1000000 | semi-sparse | both-shuffled | 14.622 ms | 13.848 ms | 1.06x | 15.70 MB | miss |
| 1000000 | semi-sparse | sorted | 12.974 ms | 14.434 ms | 0.90x | 15.70 MB | pass |
| 4000000 | anti-sparse | both-shuffled | 65.687 ms | 110.059 ms | 0.60x | 77.20 MB | pass |
| 4000000 | anti-sparse | sorted | 58.957 ms | 69.637 ms | 0.85x | 77.20 MB | pass |
| 4000000 | join-skewed | both-shuffled | 7.481 ms | 5.472 ms | 1.37x | 48.06 MB | miss |
| 4000000 | join-skewed | sorted | 6.195 ms | 5.491 ms | 1.13x | 48.06 MB | miss |
| 4000000 | join-sparse | both-shuffled | 60.859 ms | 62.047 ms | 0.98x | 64.40 MB | pass |
| 4000000 | join-sparse | sorted | 58.760 ms | 58.230 ms | 1.01x | 64.40 MB | pass |
| 4000000 | semi-sparse | both-shuffled | 59.309 ms | 57.589 ms | 1.03x | 62.80 MB | pass |
| 4000000 | semi-sparse | sorted | 58.483 ms | 52.674 ms | 1.11x | 62.80 MB | miss |

7 measured regime(s) remain above 1.05x.

Component receipts retain raw samples, versions, thread counts, exact
frame4s checksums, comparator invariants, and matching fixture hashes.
