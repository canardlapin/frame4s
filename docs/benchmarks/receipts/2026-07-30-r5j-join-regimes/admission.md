# R5j join size and key-order regime decision

This receipt is a discovery court. It admits the fixture and measurement
method, not a join optimization.

The court measures one-to-one eager join construction at 1K, 4K, 16K, 64K,
256K, 1M, and 4M rows. Each size uses sorted keys, a shuffled right side, and
both sides shuffled. All four implementations receive the same deterministic
key/value multisets; `validation.tsv` records matching permutation
fingerprints and output invariants.

The host is an Apple M3 Max with 14 logical processors. The comparators are
pandas 3.0.1, Polars 1.43.1 pinned to one thread, and Polars 1.43.1 using its
14-thread default pool. frame4s and pandas use one execution thread. Timings
stop after construction of an eager result. frame4s checksum consumption is
profiled separately and is not included in comparator ratios.

## Decision

The 1M-row point does not hide a single-threaded hash-join loss. At 1M,
frame4s takes 29.33–30.12 ms across the three key orders. It takes 0.56–0.59x
the time of single-threaded Polars. At 4M it takes 0.52–0.69x. The remaining
one-to-one Polars gap is parallel: default-threaded Polars is 2.84–3.22x faster
at 1M and 2.98–3.25x faster at 4M.

pandas changes algorithms when the keys are sorted. At 1M it takes 2.08 ms
with sorted keys, 29.48 ms with only the right side shuffled, and 44.67 ms with
both sides shuffled. frame4s is 14.32x slower than the sorted merge path, within
2% of the right-shuffled result, and 0.66x the both-shuffled time. At 4M,
frame4s is 22.51x slower than sorted pandas but 0.76–0.80x the shuffled pandas
times. Published pandas ratios must therefore keep sorted and shuffled cases
separate.

frame4s has no sustained sorted-key advantage in the current hash kernel. From
256K through 4M its three orderings are within 8% at every size. The smaller
tiers contain noisy winner changes, but no monotonic fast path. The new
scattered fixtures are sufficient to evaluate a sorted merge candidate without
using the discovery fixture as its only evidence.

Normalized allocation is independent of key order: 65.17 MB/op at 1M and
260.68 MB/op at 4M, or about 65 bytes per output row. At 1M the median build
stage takes 13.60–14.69 ms and probe takes 14.26–15.12 ms. A parallel probe can
therefore target the default-thread Polars gap, but its sequential build floor
means it must preserve the R5i allocation improvement to remain worthwhile.

## Consequence for the program

The next single-thread work should target sparse, semi, anti, and skewed joins;
one-to-one already beats pinned Polars at the large tiers. Sorted merge join
remains a separate conditional optimization, judged on at least two adjacent
sizes and checked against the shuffled fixtures. Allocation-safe parallel
probing follows the remaining single-thread work and must improve both 1M and
4M without restoring per-worker spare-capacity allocation.

See `summary.md` for every timing and ratio, `relative-regimes.tsv` for
descriptive winner ranges, `frame4s/stage-summary.tsv` for stage attribution,
and the component `environment.properties` and `validation.tsv` files for
versions, thread counts, fingerprints, and correctness evidence.
