# R5j final join parity decision

This receipt closes the join performance program with fresh comparator runs
against the admitted code at commit `b4ae0db`.

The host is an Apple M3 Max with 14 logical processors. frame4s uses its
admitted JVM scheduler, pandas 3.0.1 uses one execution thread, and Polars
1.43.1 is measured both pinned to one thread and with its 14-thread default
pool. NumPy is pinned to 2.5.1. Every implementation receives the same
deterministic key/value multisets; validation records matching output
cardinality and key fingerprints before any ratios are generated.

## One-to-one size and key-order decision

At the large tiers:

| Rows | Order | frame4s | pandas | Polars 1t | Polars default | f4s/pandas | f4s/Polars 1t | f4s/Polars default |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 1M | both shuffled | 9.846 ms | 27.653 ms | 42.144 ms | 8.351 ms | 0.36x | 0.23x | 1.18x |
| 1M | right shuffled | 9.819 ms | 23.320 ms | 40.563 ms | 8.246 ms | 0.42x | 0.24x | 1.19x |
| 1M | sorted | 9.265 ms | 1.963 ms | 41.576 ms | 7.844 ms | 4.72x | 0.22x | 1.18x |
| 4M | both shuffled | 68.388 ms | 217.476 ms | 219.440 ms | 54.082 ms | 0.31x | 0.31x | 1.26x |
| 4M | right shuffled | 67.283 ms | 176.820 ms | 223.386 ms | 54.858 ms | 0.38x | 0.30x | 1.23x |
| 4M | sorted | 37.116 ms | 7.557 ms | 208.286 ms | 48.748 ms | 4.91x | 0.18x | 0.76x |

The program exits its one-to-one targets. Shuffled frame4s is at most 0.42x
pandas, every order is at most 0.31x pinned Polars at 1M/4M, and every order is
within 1.26x default Polars. pandas' sorted merge remains 4.72--4.91x faster;
that loss is reported as a merge-join comparison, never as a hash-join loss.

The full seven-size matrix is in `summary.md` and `combined.tsv`. It keeps
sorted pandas, shuffled pandas, pinned Polars, and default Polars in separate
columns.

## Sparse and existential decision

The fresh shape court covers sparse, skewed, semi, and anti joins at 256K, 1M,
and 4M under sorted and both-shuffled keys.

- All 24 regimes beat pinned Polars, with ratios from 0.15x to 0.54x.
- Twenty of 24 regimes are within 2.0x default Polars.
- The 256K sorted sparse and semi joins are 2.37x and 2.29x default Polars.
  They take the sequential sorted merge path, where detection and fixed
  scheduling cost have not yet amortized.
- The 4M skewed joins are 2.42x shuffled and 3.57x sorted versus default
  Polars. They scan 4M left rows to emit only 1K rows; the remaining boundary
  is parallel scan bandwidth and scheduling, not hash lookup or allocation.

These four losses remain visible in `shapes/summary.md`. They document the
architectural boundary allowed by the program exit condition; they are not
averaged into a parity claim.

## Construction and consumption

Comparator ratios use eager result construction only. The fresh 1M consumption
court records both endpoints:

| Workload | Execution only | With checksum | Checksum share |
|---|---:|---:|---:|
| one-to-one | 9.373 ms | 33.888 ms | 72.3% |
| one-to-many | 13.110 ms | 37.914 ms | 65.4% |
| sparse | 5.850 ms | 8.459 ms | 30.8% |
| skewed | 0.537 ms | 0.566 ms | 5.0% |
| semi sparse | 5.916 ms | 6.951 ms | 14.9% |
| anti sparse | 6.349 ms | 13.977 ms | 54.6% |

Polars and pandas construction timings contain no equivalent serial checksum
walk, so the consumed column is validation and downstream-consumption evidence,
not a comparator numerator.

## Certification

`summary.md`, `shapes/summary.md`, and `consumption/summary.md` are the
human-readable courts. Their component directories retain raw samples,
versions, thread counts, hardware, exact frame4s checksums, comparator
invariants, allocation, stage attribution, and deterministic fingerprints.
`source-files.sha256` binds the court to the kernel, scheduler, fixtures, and
scripts used to generate it.
