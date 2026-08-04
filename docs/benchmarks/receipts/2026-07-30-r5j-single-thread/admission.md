# R5j.3 single-thread join decision

This receipt admits a narrower hash-join kernel, not a claim of universal
single-thread parity.

The admitted changes:

- allocate duplicate-tail storage only when a key actually repeats;
- use a direct lookup when the built side contains one distinct key;
- replace the hash finalizer with a cheaper mixed multiplicative hash;
- skip required-key validity checks in the build and semi/anti probe loops;
- omit redundant batch-ordinal selection arrays for single-batch inputs.

The rejected `required-key` and `required-key-helper` receipts are retained
because broader loop extraction and helper factoring lost on one-to-many or
skewed joins. They are not part of the admitted source.

## Before-and-after decision

The focused comparison uses the same 1 GiB JMH command on the baseline at
commit `1293232` and the admitted candidate. Medians are computed from five raw
measurement iterations.

| Workload at 1M | Baseline | Candidate | Speedup | Candidate allocation |
|---|---:|---:|---:|---:|
| anti sparse | 16.897 ms | 12.882 ms | 1.312x | 0.793x |
| one-to-many | 35.877 ms | 29.024 ms | 1.236x | 0.884x |
| one-to-one | 31.646 ms | 21.505 ms | 1.472x | 0.749x |
| skewed | 2.165 ms | 1.281 ms | 1.689x | 0.999x |
| sparse | 15.384 ms | 12.393 ms | 1.241x | 0.897x |
| semi sparse | 16.533 ms | 12.284 ms | 1.346x | 0.916x |

All six shapes improve at 1M. The matching 1K court also improves every shape
by 6--26%, with lower allocation throughout. Exact tables and raw JMH results
are under `focused/1m` and `focused/1k`.

## Remaining single-thread boundary

The adjacent-size, sorted-and-shuffled court compares the candidate with Polars
1.43.1 pinned to one thread. It covers sparse, semi, anti, and skewed joins at
256K, 1M, and 4M rows.

- anti passes all six regimes;
- sparse passes both 256K and both 4M regimes, but is 1.12--1.22x Polars at 1M;
- semi is near parity and changes winner with size and order, missing by
  1.06x at 1M shuffled and 1.11x at 4M sorted;
- skew passes both 256K cases and sorted 1M, but is 1.13--1.40x Polars in the
  remaining large regimes.

Seventeen of 24 regimes meet the 1.05x target. The seven misses are a documented
lower bound, not hidden by an aggregate. Their non-monotonic size and order
pattern shows that 1M is not a universal regime switch: sparse recovers at 4M,
semi remains near parity, and skew is the persistent large-row residual.

This closes the bounded R5j.3 work because the candidate improves every
same-harness shape and preserves the small tier while making the remaining
Polars boundary explicit. It does not close the default-threaded Polars gap.

## Next decision

Sorted merge join is evaluated next on the already-committed shuffled controls
and at adjacent sizes. It is reported as a conditional sorted-key optimization,
never as a hash-join speedup. Allocation-safe parallel probing follows: it must
beat the 1M and 4M default-thread Polars ratios without restoring the rejected
per-worker spare-capacity allocation.

The full shape table is in `shape-regimes/summary.md`. `candidate/` retains the
six-shape stage and allocation court, and every component receipt retains
environment, validation, and raw measurement evidence.
