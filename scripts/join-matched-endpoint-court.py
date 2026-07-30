#!/usr/bin/env python3
"""Measure semantically matched eager join and consumption endpoints."""

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
OUTPUT_COLUMNS = ("key", "leftValue", "rightKey", "rightValue")
OUTPUT_DTYPES = ("<i4", "<i8", "<i4", "<i8")
EXPECTED_SCHEMA = (
    "key:Int32:required,leftValue:Int64:required,"
    "rightKey:Int32:required,rightValue:Int64:required"
)


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


def input_digest(values: np.ndarray) -> str:
    return hashlib.sha256(
        values.astype("<i4", copy=False).tobytes()
    ).hexdigest()


def output_digest(result: object, backend: str) -> str:
    digest = hashlib.sha256()
    for name, dtype in zip(OUTPUT_COLUMNS, OUTPUT_DTYPES):
        if backend == "pandas":
            values = result[name].to_numpy(copy=False)  # type: ignore[index]
        else:
            values = result[name].to_numpy()  # type: ignore[index]
        digest.update(np.asarray(values, dtype=dtype).tobytes())
    return digest.hexdigest()


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
            "rightKey": right_keys,
            "rightValue": right_keys.astype(np.int64) * 2,
        }
    )

    def join() -> object:
        return left.merge(
            right,
            left_on="key",
            right_on="rightKey",
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
            "leftValue": pl.Series(
                "leftValue", left_keys.astype(np.int64)
            ),
        }
    )
    right = pl.DataFrame(
        {
            "rightKey": pl.Series("rightKey", right_keys),
            "rightValue": pl.Series(
                "rightValue", right_keys.astype(np.int64) * 2
            ),
        }
    )

    def join() -> object:
        return left.join(
            right,
            left_on="key",
            right_on="rightKey",
            how="inner",
            maintain_order="left",
            coalesce=False,
        )

    return join


def consume(result: object, backend: str) -> int:
    if backend == "pandas":
        return sum(
            int(result[name].sum())  # type: ignore[index]
            for name in OUTPUT_COLUMNS
        )
    import polars as pl

    sums = result.select(  # type: ignore[attr-defined]
        pl.col(name).cast(pl.Int64).sum().alias(name)
        for name in OUTPUT_COLUMNS
    ).row(0)
    return sum(int(value) for value in sums)


def schema_label(result: object, backend: str) -> str:
    if backend == "pandas":
        types = [str(result[name].dtype) for name in OUTPUT_COLUMNS]  # type: ignore[index]
        normalized = {
            "int32": "Int32",
            "int64": "Int64",
        }
    else:
        types = [str(result.schema[name]) for name in OUTPUT_COLUMNS]  # type: ignore[attr-defined]
        normalized = {
            "Int32": "Int32",
            "Int64": "Int64",
        }
    return ",".join(
        f"{name}:{normalized.get(actual, actual)}:required"
        for name, actual in zip(OUTPUT_COLUMNS, types)
    )


def validate(
    result: object,
    backend: str,
    rows: int,
    left_keys: np.ndarray,
) -> dict[str, object]:
    if backend == "pandas":
        result_columns = tuple(result.columns)  # type: ignore[attr-defined]
        output_rows = len(result)  # type: ignore[arg-type]
        output_keys = result["key"].to_numpy(copy=False)  # type: ignore[index]
        right_keys = result["rightKey"].to_numpy(copy=False)  # type: ignore[index]
    else:
        result_columns = tuple(result.columns)  # type: ignore[attr-defined]
        output_rows = result.height  # type: ignore[attr-defined]
        output_keys = result["key"].to_numpy()  # type: ignore[index]
        right_keys = result["rightKey"].to_numpy()  # type: ignore[index]
    if result_columns != OUTPUT_COLUMNS:
        raise RuntimeError(
            f"{backend} output columns {result_columns} != {OUTPUT_COLUMNS}"
        )
    if output_rows != rows:
        raise RuntimeError(f"{backend} output rows {output_rows} != {rows}")
    if not np.array_equal(output_keys, left_keys):
        raise RuntimeError(f"{backend} did not preserve exact left order")
    if not np.array_equal(right_keys, left_keys):
        raise RuntimeError(f"{backend} joined incorrect right keys")
    actual_schema = schema_label(result, backend)
    if actual_schema != EXPECTED_SCHEMA:
        raise RuntimeError(
            f"{backend} schema {actual_schema} != {EXPECTED_SCHEMA}"
        )
    expected_sum = rows * (rows - 1) // 2 * 5
    actual_sum = consume(result, backend)
    if actual_sum != expected_sum:
        raise RuntimeError(
            f"{backend} column sum {actual_sum} != {expected_sum}"
        )
    return {
        "output_rows": output_rows,
        "schema": actual_schema,
        "ordered_output_sha256": output_digest(result, backend),
        "column_sum": actual_sum,
    }


def measure(
    operation: Callable[[], object], quick: bool
) -> tuple[int, list[float]]:
    warmups = 1 if quick else 3
    for _ in range(warmups):
        operation()
    target_seconds = 0.05 if quick else 0.25
    sample_count = 3 if quick else 7
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


def write_receipt(args: argparse.Namespace) -> None:
    raw = args.receipt / "raw"
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
            join = operation_factory(left_keys, right_keys)
            checked_result = join()
            checked = validate(
                checked_result, args.backend, rows, left_keys
            )
            validations.append(
                {
                    "rows": rows,
                    "order": order,
                    **checked,
                    "left_keys_sha256": input_digest(left_keys),
                    "right_keys_sha256": input_digest(right_keys),
                }
            )

            endpoint_operations: tuple[
                tuple[str, Callable[[], object]], ...
            ] = (
                ("deep-materialized", join),
                (
                    "matched-consumption",
                    lambda join=join: consume(join(), args.backend),
                ),
            )
            for endpoint, endpoint_operation in endpoint_operations:
                loops, values = measure(endpoint_operation, args.quick)
                for sample, milliseconds in enumerate(values, start=1):
                    samples.append(
                        {
                            "rows": rows,
                            "order": order,
                            "endpoint": endpoint,
                            "sample": sample,
                            "loops": loops,
                            "milliseconds_per_operation": milliseconds,
                        }
                    )
                metrics.append(
                    {
                        "rows": rows,
                        "order": order,
                        "endpoint": endpoint,
                        "milliseconds": statistics.median(values),
                        "minimum_ms": min(values),
                        "maximum_ms": max(values),
                    }
                )
            del join, checked_result, left_keys, right_keys
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
        "suite": "frame4s-join-matched-endpoint-comparator-court",
        "backend": args.backend,
        "backend.version": backend_version,
        "threads": threads,
        "quick": str(args.quick).lower(),
        "process_round": args.process_round,
        "sequence_position": args.sequence_position,
        "sizes": ",".join(str(value) for value in args.sizes),
        "orders": ",".join(args.orders),
        "left.seed.unsigned": LEFT_SEED,
        "right.seed.unsigned": RIGHT_SEED,
        "python.version": platform.python_version(),
        "numpy.version": np.__version__,
        "os": platform.platform(),
        "machine": platform.machine(),
        "processor": platform.processor() or "unrecorded",
        "numeric_backend_threads": os.environ.get(
            "OMP_NUM_THREADS", "unrecorded"
        ),
        "schema": "key:Int32,leftValue:Int64,rightKey:Int32,rightValue:Int64",
        "order_contract": "stable-left",
        "deep-materialized": "completed-eager-dataframe",
        "matched-consumption": "eager-join-plus-four-column-sum",
        "input_creation": "outside-timing",
    }
    (args.receipt / "environment.properties").write_text(
        "".join(f"{key}={value}\n" for key, value in environment.items()),
        encoding="utf-8",
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--backend", choices=("pandas", "polars"), required=True)
    parser.add_argument("--sizes", default="1000000,4000000")
    parser.add_argument("--orders", default=",".join(ORDERS))
    parser.add_argument("--expected-threads", type=int)
    parser.add_argument("--process-round", type=int, default=1)
    parser.add_argument("--sequence-position", type=int, default=1)
    parser.add_argument("--quick", action="store_true")
    args = parser.parse_args()
    args.sizes = [
        int(value) for value in args.sizes.split(",") if value.strip()
    ]
    args.orders = [
        value.strip() for value in args.orders.split(",") if value.strip()
    ]
    if not args.sizes or any(value <= 0 for value in args.sizes):
        raise SystemExit("--sizes must contain positive integers")
    invalid_orders = set(args.orders) - set(ORDERS)
    if invalid_orders:
        raise SystemExit(f"unsupported --orders: {sorted(invalid_orders)}")
    if args.process_round <= 0 or args.sequence_position <= 0:
        raise SystemExit("process round and sequence position must be positive")
    return args


if __name__ == "__main__":
    write_receipt(parse_args())
