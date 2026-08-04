#!/usr/bin/env python3
"""Measure Polars sparse and existential joins across size and key order."""

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
import polars as pl


MASK_64 = (1 << 64) - 1
LEFT_SEED = 0x6A09E667F3BCC909
RIGHT_SEED = 0xBB67AE8584CAA73B
GAMMA = 0x9E3779B97F4A7C15
WORKLOADS = ("join-sparse", "join-skewed", "semi-sparse", "anti-sparse")
ORDERS = ("sorted", "both-shuffled")


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


def digest(values: np.ndarray) -> str:
    return hashlib.sha256(values.astype("<i4", copy=False).tobytes()).hexdigest()


def fixture(
    rows: int, workload: str, order: str
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    left_keys = (
        np.arange(rows, dtype=np.int32)
        if order == "sorted"
        else permutation(rows, LEFT_SEED)
    )
    right_rows = min(rows, 1000) if workload == "join-skewed" else rows // 10
    right_rows = max(1, right_rows)
    if workload == "join-skewed":
        right_keys = np.zeros(right_rows, dtype=np.int32)
        right_values = (
            np.arange(right_rows, dtype=np.int32)
            if order == "sorted"
            else permutation(right_rows, RIGHT_SEED)
        )
    else:
        ordinals = (
            np.arange(right_rows, dtype=np.int32)
            if order == "sorted"
            else permutation(right_rows, RIGHT_SEED)
        )
        right_keys = ordinals * np.int32(10)
        right_values = right_keys * np.int32(2)
    return left_keys, right_keys, right_values


def operation(
    left_keys: np.ndarray,
    right_keys: np.ndarray,
    right_values: np.ndarray,
    workload: str,
) -> Callable[[], pl.DataFrame]:
    left = pl.DataFrame(
        {
            "key": pl.Series("key", left_keys),
            "leftValue": pl.Series(
                "leftValue", left_keys.astype(np.int64) * 3
            ),
        }
    )
    right = pl.DataFrame(
        {
            "rightKey": pl.Series("rightKey", right_keys),
            "rightValue": pl.Series(
                "rightValue", right_values.astype(np.int64)
            ),
        }
    )
    how = {
        "join-sparse": "inner",
        "join-skewed": "inner",
        "semi-sparse": "semi",
        "anti-sparse": "anti",
    }[workload]

    def join() -> pl.DataFrame:
        return left.join(
            right,
            left_on="key",
            right_on="rightKey",
            how=how,  # type: ignore[arg-type]
        )

    return join


def validate(
    result: pl.DataFrame, rows: int, workload: str
) -> dict[str, int]:
    right_rows = max(
        1, min(rows, 1000) if workload == "join-skewed" else rows // 10
    )
    sparse_key_sum = 10 * right_rows * (right_rows - 1) // 2
    all_key_sum = rows * (rows - 1) // 2
    if workload == "join-skewed":
        expected = {
            "output_rows": right_rows,
            "key_sum": 0,
            "left_value_sum": 0,
            "right_value_sum": right_rows * (right_rows - 1) // 2,
        }
    elif workload == "join-sparse":
        expected = {
            "output_rows": right_rows,
            "key_sum": sparse_key_sum,
            "left_value_sum": sparse_key_sum * 3,
            "right_value_sum": sparse_key_sum * 2,
        }
    elif workload == "semi-sparse":
        expected = {
            "output_rows": right_rows,
            "key_sum": sparse_key_sum,
            "left_value_sum": sparse_key_sum * 3,
            "right_value_sum": 0,
        }
    else:
        expected = {
            "output_rows": rows - right_rows,
            "key_sum": all_key_sum - sparse_key_sum,
            "left_value_sum": (all_key_sum - sparse_key_sum) * 3,
            "right_value_sum": 0,
        }
    actual = {
        "output_rows": result.height,
        "key_sum": int(result["key"].cast(pl.Int64).sum()),
        "left_value_sum": int(result["leftValue"].sum()),
        "right_value_sum": (
            int(result["rightValue"].sum())
            if "rightValue" in result.columns
            else 0
        ),
    }
    if actual != expected:
        raise RuntimeError(f"{workload} validation failed: {actual} != {expected}")
    return actual


def measure(
    join: Callable[[], pl.DataFrame], quick: bool
) -> tuple[int, list[float]]:
    for _ in range(2 if quick else 5):
        join()
    timer = timeit.Timer(join)
    target = 0.05 if quick else 0.25
    loops = 1
    while loops < 8192 and timer.timeit(number=loops) < target:
        loops *= 2
    gc.collect()
    samples = [
        seconds * 1000.0 / loops
        for seconds in timer.repeat(3 if quick else 9, loops)
    ]
    return loops, samples


def write_receipt(args: argparse.Namespace) -> None:
    raw = args.receipt / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    samples: list[dict[str, object]] = []
    metrics: list[dict[str, object]] = []
    validations: list[dict[str, object]] = []
    threads = pl.thread_pool_size()
    if args.expected_threads is not None and threads != args.expected_threads:
        raise RuntimeError(
            f"Polars thread pool is {threads}; expected {args.expected_threads}"
        )

    for rows in args.sizes:
        for workload in args.workloads:
            for order in args.orders:
                left_keys, right_keys, right_values = fixture(
                    rows, workload, order
                )
                join = operation(
                    left_keys, right_keys, right_values, workload
                )
                checked = validate(join(), rows, workload)
                validations.append(
                    {
                        "rows": rows,
                        "workload": workload,
                        "order": order,
                        **checked,
                        "left_keys_sha256": digest(left_keys),
                        "right_keys_sha256": digest(right_keys),
                        "right_values_sha256": digest(right_values),
                    }
                )
                loops, values = measure(join, args.quick)
                for sample, milliseconds in enumerate(values, start=1):
                    samples.append(
                        {
                            "rows": rows,
                            "workload": workload,
                            "order": order,
                            "sample": sample,
                            "loops": loops,
                            "milliseconds_per_operation": milliseconds,
                        }
                    )
                metrics.append(
                    {
                        "rows": rows,
                        "workload": workload,
                        "order": order,
                        "milliseconds": statistics.median(values),
                    }
                )
                del join, left_keys, right_keys, right_values
                gc.collect()

    for path, rows, delimiter in (
        (raw / "samples.csv", samples, ","),
        (args.receipt / "metrics.tsv", metrics, "\t"),
        (args.receipt / "validation.tsv", validations, "\t"),
    ):
        with path.open("w", newline="", encoding="utf-8") as handle:
            writer = csv.DictWriter(
                handle,
                fieldnames=rows[0].keys(),
                delimiter=delimiter,
                lineterminator="\n",
            )
            writer.writeheader()
            writer.writerows(rows)

    environment = {
        "receipt_format": "1",
        "suite": "frame4s-join-shape-polars-court",
        "backend": "polars",
        "backend.version": pl.__version__,
        "threads": threads,
        "quick": str(args.quick).lower(),
        "sizes": ",".join(str(value) for value in args.sizes),
        "workloads": ",".join(args.workloads),
        "orders": ",".join(args.orders),
        "python.version": platform.python_version(),
        "numpy.version": np.__version__,
        "os": platform.platform(),
        "machine": platform.machine(),
        "numeric_backend_threads": os.environ.get(
            "OMP_NUM_THREADS", "unrecorded"
        ),
        "timing": "timeit,completed-eager-result",
    }
    (args.receipt / "environment.properties").write_text(
        "".join(f"{key}={value}\n" for key, value in environment.items()),
        encoding="utf-8",
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--sizes", default="256000,1000000,4000000")
    parser.add_argument("--workloads", default=",".join(WORKLOADS))
    parser.add_argument("--orders", default=",".join(ORDERS))
    parser.add_argument("--expected-threads", type=int)
    parser.add_argument("--quick", action="store_true")
    args = parser.parse_args()
    args.sizes = [int(value) for value in args.sizes.split(",") if value]
    args.workloads = [
        value for value in args.workloads.split(",") if value
    ]
    args.orders = [value for value in args.orders.split(",") if value]
    if set(args.workloads) - set(WORKLOADS):
        raise SystemExit("unsupported workload")
    if set(args.orders) - set(ORDERS):
        raise SystemExit("unsupported order")
    return args


if __name__ == "__main__":
    write_receipt(parse_args())
