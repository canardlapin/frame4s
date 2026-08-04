#!/usr/bin/env python3
"""Measure one-to-one join size and key-order regimes for pandas or Polars."""

from __future__ import annotations

import argparse
import csv
import gc
import hashlib
import os
import platform
import statistics
import timeit
from pathlib import Path
from typing import Callable

import numpy as np


MASK_64 = (1 << 64) - 1
LEFT_SEED = 0x6A09E667F3BCC909
RIGHT_SEED = 0xBB67AE8584CAA73B
GAMMA = 0x9E3779B97F4A7C15
ORDERS = ("sorted", "right-shuffled", "both-shuffled")


def permutation(size: int, seed: int) -> np.ndarray:
    values = np.arange(size, dtype=np.int32)
    state = seed
    for index in range(size - 1, 0, -1):
        state = (state + GAMMA) & MASK_64
        mixed = state
        mixed = ((mixed ^ (mixed >> 30)) * 0xBF58476D1CE4E5B9) & MASK_64
        mixed = ((mixed ^ (mixed >> 27)) * 0x94D049BB133111EB) & MASK_64
        mixed ^= mixed >> 31
        selected = mixed % (index + 1)
        values[index], values[selected] = values[selected], values[index]
    return values


def ordered_keys(rows: int, order: str, left: bool) -> np.ndarray:
    if order == "sorted" or (order == "right-shuffled" and left):
        return np.arange(rows, dtype=np.int32)
    if order == "right-shuffled":
        return permutation(rows, RIGHT_SEED)
    if order == "both-shuffled":
        return permutation(rows, LEFT_SEED if left else RIGHT_SEED)
    raise ValueError(f"unsupported key order: {order}")


def digest(values: np.ndarray) -> str:
    little_endian = values.astype("<i4", copy=False)
    return hashlib.sha256(little_endian.tobytes()).hexdigest()


def measure(
    operation: Callable[[], object], quick: bool
) -> tuple[int, list[float]]:
    warmups = 2 if quick else 5
    for _ in range(warmups):
        operation()
    target_seconds = 0.05 if quick else 0.25
    sample_count = 3 if quick else 9
    timer = timeit.Timer(operation)
    loops = 1
    while loops < 8192 and timer.timeit(number=loops) < target_seconds:
        loops *= 2
    gc.collect()
    samples = [
        seconds * 1000.0 / loops
        for seconds in timer.repeat(sample_count, loops)
    ]
    return loops, samples


def pandas_operation(
    left_keys: np.ndarray, right_keys: np.ndarray
) -> Callable[[], object]:
    import pandas as pd

    left = pd.DataFrame(
        {
            "key": left_keys,
            "leftValue": left_keys.astype(np.int64),
        }
    )
    right = pd.DataFrame(
        {
            "key": right_keys,
            "rightValue": right_keys.astype(np.int64) * 2,
        }
    )

    def join() -> object:
        return left.merge(
            right,
            on="key",
            how="inner",
            sort=False,
        )

    return join


def polars_operation(
    left_keys: np.ndarray, right_keys: np.ndarray
) -> Callable[[], object]:
    import polars as pl

    left = pl.DataFrame(
        {
            "key": pl.Series("key", left_keys),
            "leftValue": pl.Series("leftValue", left_keys.astype(np.int64)),
        }
    )
    right = pl.DataFrame(
        {
            "key": pl.Series("key", right_keys),
            "rightValue": pl.Series(
                "rightValue", right_keys.astype(np.int64) * 2
            ),
        }
    )

    def join() -> object:
        return left.join(right, on="key", how="inner")

    return join


def validate(result: object, backend: str, rows: int) -> dict[str, int]:
    expected_sum = rows * (rows - 1) // 2
    if backend == "pandas":
        actual = {
            "output_rows": len(result),  # type: ignore[arg-type]
            "key_sum": int(result["key"].sum()),  # type: ignore[index]
            "left_value_sum": int(result["leftValue"].sum()),  # type: ignore[index]
            "right_value_sum": int(result["rightValue"].sum()),  # type: ignore[index]
        }
    else:
        import polars as pl

        actual = {
            "output_rows": result.height,  # type: ignore[attr-defined]
            "key_sum": int(result["key"].cast(pl.Int64).sum()),  # type: ignore[index]
            "left_value_sum": int(result["leftValue"].sum()),  # type: ignore[index]
            "right_value_sum": int(result["rightValue"].sum()),  # type: ignore[index]
        }
    expected = {
        "output_rows": rows,
        "key_sum": expected_sum,
        "left_value_sum": expected_sum,
        "right_value_sum": expected_sum * 2,
    }
    if actual != expected:
        raise RuntimeError(f"{backend} validation failed: {actual} != {expected}")
    return actual


def write_receipt(args: argparse.Namespace) -> None:
    receipt = args.receipt
    raw = receipt / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    samples: list[dict[str, object]] = []
    metrics: list[dict[str, object]] = []
    validations: list[dict[str, object]] = []

    if args.backend == "pandas":
        import pandas as pd

        backend_version = pd.__version__
        threads = 1
        operation_factory = pandas_operation
    else:
        import polars as pl

        backend_version = pl.__version__
        threads = pl.thread_pool_size()
        operation_factory = polars_operation
        if args.expected_threads is not None and threads != args.expected_threads:
            raise RuntimeError(
                f"Polars thread pool is {threads}; expected {args.expected_threads}"
            )

    for rows in args.sizes:
        for order in args.orders:
            left_keys = ordered_keys(rows, order, left=True)
            right_keys = ordered_keys(rows, order, left=False)
            operation = operation_factory(left_keys, right_keys)
            validation = validate(operation(), args.backend, rows)
            validations.append(
                {
                    "rows": rows,
                    "order": order,
                    **validation,
                    "left_keys_sha256": digest(left_keys),
                    "right_keys_sha256": digest(right_keys),
                }
            )
            loops, values = measure(operation, args.quick)
            for sample, milliseconds in enumerate(values, start=1):
                samples.append(
                    {
                        "rows": rows,
                        "order": order,
                        "sample": sample,
                        "loops": loops,
                        "milliseconds_per_operation": milliseconds,
                    }
                )
            median_ms = statistics.median(values)
            metrics.append(
                {
                    "rows": rows,
                    "order": order,
                    "milliseconds": median_ms,
                    "nanoseconds_per_row": median_ms * 1_000_000.0 / rows,
                    "minimum_ms": min(values),
                    "maximum_ms": max(values),
                }
            )
            del operation, left_keys, right_keys
            gc.collect()

    with (raw / "samples.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(
            handle, fieldnames=samples[0].keys(), lineterminator="\n"
        )
        writer.writeheader()
        writer.writerows(samples)
    with (receipt / "metrics.tsv").open(
        "w", newline="", encoding="utf-8"
    ) as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=metrics[0].keys(),
            delimiter="\t",
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerows(metrics)
    with (receipt / "validation.tsv").open(
        "w", newline="", encoding="utf-8"
    ) as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=validations[0].keys(),
            delimiter="\t",
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerows(validations)

    environment = {
        "receipt_format": "1",
        "suite": "frame4s-join-regime-comparator-court",
        "backend": args.backend,
        "backend.version": backend_version,
        "threads": threads,
        "quick": str(args.quick).lower(),
        "sizes": ",".join(str(value) for value in args.sizes),
        "orders": ",".join(args.orders),
        "left.seed.unsigned": LEFT_SEED,
        "right.seed.unsigned": RIGHT_SEED,
        "python.version": platform.python_version(),
        "numpy.version": np.__version__,
        "os": platform.platform(),
        "machine": platform.machine(),
        "processor": platform.processor() or "unrecorded",
        "numeric_backend_threads": os.environ.get("OMP_NUM_THREADS", "unrecorded"),
        "timing": "timeit,completed-eager-result",
        "fixture": "identical-key-value-multiset; only row ordering changes",
    }
    (receipt / "environment.properties").write_text(
        "".join(f"{key}={value}\n" for key, value in environment.items()),
        encoding="utf-8",
    )

    lines = [
        f"# {args.backend} join size and key-order regime court",
        "",
        "Quick provisional receipt." if args.quick else "Full receipt.",
        "",
        f"Thread count: {threads}. Every row eagerly constructs a completed join result.",
        "The key/value multiset is identical across orders; only row order changes.",
        "",
        "| Rows | Key order | Execution ms/op | ns/row |",
        "|---:|---|---:|---:|",
    ]
    for metric in metrics:
        lines.append(
            f"| {metric['rows']} | {metric['order']} | "
            f"{float(metric['milliseconds']):.3f} | "
            f"{float(metric['nanoseconds_per_row']):.2f} |"
        )
    lines.extend(
        [
            "",
            "Individual samples are in `raw/samples.csv`; exact cardinality,",
            "value-binding invariants, and deterministic key fingerprints are in",
            "`validation.tsv`.",
            "",
        ]
    )
    (receipt / "summary.md").write_text("\n".join(lines), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--backend", choices=("pandas", "polars"), required=True)
    parser.add_argument(
        "--sizes",
        default="1000,4000,16000,64000,256000,1000000,4000000",
    )
    parser.add_argument("--orders", default=",".join(ORDERS))
    parser.add_argument("--expected-threads", type=int)
    parser.add_argument("--quick", action="store_true")
    arguments = parser.parse_args()
    arguments.sizes = [
        int(value) for value in arguments.sizes.split(",") if value.strip()
    ]
    arguments.orders = [
        value.strip() for value in arguments.orders.split(",") if value.strip()
    ]
    if not arguments.sizes or any(value <= 0 for value in arguments.sizes):
        raise SystemExit("--sizes must contain positive integers")
    invalid_orders = set(arguments.orders) - set(ORDERS)
    if invalid_orders:
        raise SystemExit(f"unsupported --orders: {sorted(invalid_orders)}")
    return arguments


if __name__ == "__main__":
    write_receipt(parse_args())
