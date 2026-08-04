#!/usr/bin/env python3
"""Measure how key order affects pandas and Polars equi joins.

The committed comparison fixtures use monotonic one-to-one keys. This court
holds the key/value multiset and output cardinality fixed while changing only
row order. It records every timing sample, deterministic permutation hashes,
runtime versions, and output invariants so an order-sensitive fast path cannot
be inferred from an unrepeatable console experiment.
"""

from __future__ import annotations

import argparse
import csv
import gc
import hashlib
import os
import platform
import statistics
import timeit
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import numpy as np
import pandas as pd
import polars as pl


EXPECTED_PANDAS = "3.0.1"
EXPECTED_POLARS = "1.43.1"
SEED = 20260730


@dataclass(frozen=True)
class Case:
    name: str
    pandas: Callable[[], pd.DataFrame]
    polars: Callable[[], pl.DataFrame]


def digest(values: np.ndarray) -> str:
    return hashlib.sha256(values.tobytes()).hexdigest()


def cases(rows: int) -> tuple[list[Case], dict[str, str]]:
    rng = np.random.default_rng(SEED)
    keys = np.arange(rows, dtype=np.int32)
    left_values = np.arange(rows, dtype=np.int64)
    right_values = left_values * 2
    left_permutation = rng.permutation(rows)
    right_permutation = rng.permutation(rows)

    def pandas_frame(
        selected_keys: np.ndarray,
        selected_values: np.ndarray,
        key_name: str,
        value_name: str,
    ) -> pd.DataFrame:
        return pd.DataFrame({key_name: selected_keys, value_name: selected_values})

    def polars_frame(
        selected_keys: np.ndarray,
        selected_values: np.ndarray,
        key_name: str,
        value_name: str,
    ) -> pl.DataFrame:
        return pl.DataFrame(
            {
                key_name: pl.Series(key_name, selected_keys),
                value_name: pl.Series(value_name, selected_values),
            }
        )

    orders = {
        "sorted": (np.arange(rows), np.arange(rows)),
        "right-shuffled": (np.arange(rows), right_permutation),
        "both-shuffled": (left_permutation, right_permutation),
    }
    output: list[Case] = []
    for name, (left_order, right_order) in orders.items():
        pandas_left = pandas_frame(
            keys[left_order], left_values[left_order], "key", "leftValue"
        )
        pandas_right = pandas_frame(
            keys[right_order], right_values[right_order], "rightKey", "rightValue"
        )
        polars_left = polars_frame(
            keys[left_order], left_values[left_order], "key", "leftValue"
        )
        polars_right = polars_frame(
            keys[right_order], right_values[right_order], "rightKey", "rightValue"
        )

        def pandas_join(
            left: pd.DataFrame = pandas_left, right: pd.DataFrame = pandas_right
        ) -> pd.DataFrame:
            return left.merge(
                right,
                left_on="key",
                right_on="rightKey",
                how="inner",
                sort=False,
            )

        def polars_join(
            left: pl.DataFrame = polars_left, right: pl.DataFrame = polars_right
        ) -> pl.DataFrame:
            return left.join(
                right,
                left_on="key",
                right_on="rightKey",
                how="inner",
                maintain_order="none",
            )

        output.append(Case(name, pandas_join, polars_join))

    fingerprints = {
        "keys.sha256": digest(keys),
        "left_permutation.sha256": digest(left_permutation),
        "right_permutation.sha256": digest(right_permutation),
    }
    return output, fingerprints


def validate_pandas(frame: pd.DataFrame, rows: int) -> dict[str, int]:
    expected_left = rows * (rows - 1) // 2
    expected_right = rows * (rows - 1)
    actual = {
        "rows": len(frame),
        "key_sum": int(frame["key"].sum()),
        "left_value_sum": int(frame["leftValue"].sum()),
        "right_value_sum": int(frame["rightValue"].sum()),
    }
    expected = {
        "rows": rows,
        "key_sum": expected_left,
        "left_value_sum": expected_left,
        "right_value_sum": expected_right,
    }
    if actual != expected:
        raise RuntimeError(f"pandas validation failed: {actual} != {expected}")
    return actual


def validate_polars(frame: pl.DataFrame, rows: int) -> dict[str, int]:
    expected_left = rows * (rows - 1) // 2
    expected_right = rows * (rows - 1)
    actual = {
        "rows": frame.height,
        # Polars preserves the Int32 key dtype through the join, and `sum`
        # consequently wraps at Int32 width. Widen before reducing so the
        # invariant validates the key multiset rather than its overflow.
        "key_sum": int(frame["key"].cast(pl.Int64).sum()),
        "left_value_sum": int(frame["leftValue"].sum()),
        "right_value_sum": int(frame["rightValue"].sum()),
    }
    expected = {
        "rows": rows,
        "key_sum": expected_left,
        "left_value_sum": expected_left,
        "right_value_sum": expected_right,
    }
    if actual != expected:
        raise RuntimeError(f"Polars validation failed: {actual} != {expected}")
    return actual


def measure(
    operation: Callable[[], object],
    target_seconds: float,
    sample_count: int,
) -> tuple[int, list[float]]:
    for _ in range(10):
        operation()
    timer = timeit.Timer(operation)
    loops = 1
    while loops < 8192 and timer.timeit(number=loops) < target_seconds:
        loops *= 2
    gc.collect()
    return loops, [seconds * 1000.0 / loops for seconds in timer.repeat(sample_count, loops)]


def write_receipt(args: argparse.Namespace) -> None:
    if pd.__version__ != EXPECTED_PANDAS:
        raise RuntimeError(f"pandas={pd.__version__}; expected {EXPECTED_PANDAS}")
    if pl.__version__ != EXPECTED_POLARS:
        raise RuntimeError(f"polars={pl.__version__}; expected {EXPECTED_POLARS}")
    if pl.thread_pool_size() != 1:
        raise RuntimeError(
            f"Polars thread pool is {pl.thread_pool_size()}, expected exactly 1"
        )

    receipt = args.receipt
    raw = receipt / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    target_seconds = 0.05 if args.quick else 0.25
    sample_count = 3 if args.quick else 9
    fixtures, fingerprints = cases(args.rows)
    samples: list[dict[str, object]] = []
    summaries: list[dict[str, object]] = []
    validations: list[dict[str, object]] = []

    for case in fixtures:
        backends = (
            ("pandas", case.pandas, validate_pandas),
            ("polars", case.polars, validate_polars),
        )
        for backend, operation, validator in backends:
            validation = validator(operation(), args.rows)
            validations.append({"backend": backend, "order": case.name, **validation})
            loops, values = measure(operation, target_seconds, sample_count)
            for sample, milliseconds in enumerate(values, start=1):
                samples.append(
                    {
                        "backend": backend,
                        "order": case.name,
                        "sample": sample,
                        "loops": loops,
                        "milliseconds_per_operation": milliseconds,
                    }
                )
            summaries.append(
                {
                    "backend": backend,
                    "order": case.name,
                    "median_ms": statistics.median(values),
                    "min_ms": min(values),
                    "max_ms": max(values),
                    "samples": sample_count,
                    "loops_per_sample": loops,
                }
            )

    with (raw / "samples.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=samples[0].keys())
        writer.writeheader()
        writer.writerows(samples)
    with (raw / "timings.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=summaries[0].keys())
        writer.writeheader()
        writer.writerows(summaries)
    with (receipt / "validation.tsv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle, fieldnames=validations[0].keys(), delimiter="\t"
        )
        writer.writeheader()
        writer.writerows(validations)

    environment = {
        "receipt_format": "1",
        "suite": "frame4s-join-order-court",
        "quick": str(args.quick).lower(),
        "rows": str(args.rows),
        "seed": str(SEED),
        "python.version": platform.python_version(),
        "numpy.version": np.__version__,
        "pandas.version": pd.__version__,
        "polars.version": pl.__version__,
        "polars.threads": str(pl.thread_pool_size()),
        "os": platform.platform(),
        "machine": platform.machine(),
        "processor": platform.processor() or "unrecorded",
        "timing": (
            f"timeit,{sample_count}-sample-median,target={target_seconds}s,"
            "completed-eager-result"
        ),
        **fingerprints,
    }
    (receipt / "environment.properties").write_text(
        "".join(f"{key}={value}\n" for key, value in environment.items()),
        encoding="utf-8",
    )

    by_backend_order = {
        (str(row["backend"]), str(row["order"])): row for row in summaries
    }
    lines = [
        "# Join key-order sensitivity receipt",
        "",
        (
            "Quick wiring receipt; timings are provisional."
            if args.quick
            else "Full deterministic order-sensitivity receipt."
        ),
        "",
        (
            f"Both comparators eagerly join the same {args.rows}-row one-to-one key/value "
            f"multiset. Only row ordering changes; seed `{SEED}` and permutation hashes "
            "are recorded in `environment.properties`."
        ),
        "",
        "| Key order | pandas median | Polars median |",
        "|---|---:|---:|",
    ]
    for order in ("sorted", "right-shuffled", "both-shuffled"):
        pandas = by_backend_order[("pandas", order)]
        polars = by_backend_order[("polars", order)]
        lines.append(
            f"| {order} | {float(pandas['median_ms']):.3f} ms | "
            f"{float(polars['median_ms']):.3f} ms |"
        )
    lines.extend(
        [
            "",
            "The experiment establishes sensitivity to key ordering. It does not by itself",
            "identify either comparator's internal physical join algorithm.",
            "",
            "Every individual timing is in `raw/samples.csv`; aggregate medians and ranges",
            "are in `raw/timings.csv`; cardinality and value-binding invariants are in",
            "`validation.tsv`.",
            "",
        ]
    )
    (receipt / "summary.md").write_text("\n".join(lines), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--rows", type=int, default=1_000_000)
    parser.add_argument("--quick", action="store_true")
    arguments = parser.parse_args()
    if arguments.rows <= 0:
        raise SystemExit("--rows must be positive")
    return arguments


if __name__ == "__main__":
    write_receipt(parse_args())
