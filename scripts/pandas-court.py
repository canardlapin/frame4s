#!/usr/bin/env python3
"""Separate-process Pandas comparison court for frame4s.

This court intentionally does not invoke Python from JMH. It measures eager Pandas
operations in one pinned Python process, validates completed output, and can compare
its medians with a frame4s JMH JSON receipt after both courts have finished.
"""

from __future__ import annotations

import argparse
import csv
import gc
import json
import os
import platform
import statistics
import timeit
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import numpy as np
import pandas as pd


MASK_64 = (1 << 64) - 1
NULL_HASH = 0x61C8864680B583EB
EXPECTED_NUMPY = "2.4.3"
EXPECTED_PANDAS = "3.0.1"


@dataclass(frozen=True)
class Workload:
    name: str
    oracle: str
    run: Callable[[], pd.DataFrame]
    expected_rows: int
    expected_columns: tuple[str, ...]
    exact_checksum: bool = True


def java_string_hash(value: str) -> int:
    result = 0
    encoded = value.encode("utf-16-be")
    for index in range(0, len(encoded), 2):
        unit = (encoded[index] << 8) | encoded[index + 1]
        result = ((result * 31) + unit) & 0xFFFFFFFF
    return result if result < (1 << 31) else result - (1 << 32)


def scalar_hash(value: object) -> int:
    if value is None or value is pd.NA:
        return NULL_HASH
    if isinstance(value, (float, np.floating)):
        if pd.isna(value):
            return NULL_HASH
        return int(np.float64(value).view(np.uint64))
    if isinstance(value, (bool, np.bool_)):
        return 1 if bool(value) else 2
    if isinstance(value, (int, np.integer)):
        return int(value) & MASK_64
    if isinstance(value, str):
        return java_string_hash(value) & MASK_64
    raise TypeError(f"unsupported checksum scalar {type(value)!r}")


def checksum(frame: pd.DataFrame) -> str:
    result = len(frame) & MASK_64
    for row in frame.itertuples(index=False, name=None):
        for value in row:
            result = ((result * 31) + scalar_hash(value)) & MASK_64
    return str(result)


def fixtures(rows: int) -> tuple[dict[str, pd.DataFrame], dict[str, Workload]]:
    ids = np.arange(rows, dtype=np.int32)
    groups = np.asarray([f"g{index % 16}" for index in range(rows)], dtype=object)
    values = np.arange(rows, dtype=np.float64) / 8.0
    values[np.arange(rows) % 7 == 0] = np.nan
    facts = pd.DataFrame({"id": ids, "group": groups, "value": values})
    left = pd.DataFrame(
        {"key": ids, "leftValue": np.arange(rows, dtype=np.int64)}
    )
    right_one = pd.DataFrame(
        {
            "rightKey": ids,
            "rightValue": np.arange(rows, dtype=np.int64) * 2,
        }
    )
    right_many = pd.DataFrame(
        {
            "rightKey": np.arange(rows, dtype=np.int32) // 3,
            "rightValue": np.arange(rows, dtype=np.int64) * 3,
        }
    )
    sparse_rows = max(1, rows // 10)
    right_sparse = pd.DataFrame(
        {
            "rightKey": np.arange(sparse_rows, dtype=np.int32) * 10,
            "rightValue": np.arange(sparse_rows, dtype=np.int64),
        }
    )
    skew_rows = max(1, min(rows, 1000))
    right_skew = pd.DataFrame(
        {
            "rightKey": np.zeros(skew_rows, dtype=np.int32),
            "rightValue": np.arange(skew_rows, dtype=np.int64),
        }
    )

    def primitive() -> pd.DataFrame:
        return facts.loc[:, ["id"]].copy()

    def fused() -> pd.DataFrame:
        result = facts.loc[facts["id"] >= rows // 2, ["id"]].copy()
        result["next"] = result["id"] + 1
        return result

    def grouped_sum() -> pd.DataFrame:
        return (
            facts.groupby("group", sort=False, observed=True, dropna=False)["value"]
            .sum(min_count=1)
            .reset_index(name="sum")
        )

    def grouped_full() -> pd.DataFrame:
        return (
            facts.groupby("group", sort=False, observed=True, dropna=False)["value"]
            .agg(
                n="size",
                sum="sum",
                mean="mean",
                variancePop=lambda values: values.var(ddof=0),
            )
            .reset_index()
        )

    def join(right: pd.DataFrame) -> pd.DataFrame:
        return left.merge(
            right,
            left_on="key",
            right_on="rightKey",
            how="inner",
            sort=False,
        )

    def distinct() -> pd.DataFrame:
        return right_many.loc[:, ["rightKey"]].drop_duplicates(ignore_index=True)

    def semi() -> pd.DataFrame:
        return left.loc[left["key"].isin(right_sparse["rightKey"])].copy()

    def anti() -> pd.DataFrame:
        return left.loc[~left["key"].isin(right_sparse["rightKey"])].copy()

    def union() -> pd.DataFrame:
        return pd.concat([left, left], ignore_index=True)

    data = {
        "facts": facts,
        "left": left,
        "right_one": right_one,
        "right_many": right_many,
        "right_sparse": right_sparse,
        "right_skew": right_skew,
    }
    workloads = {
        "primitiveMaterializedProjection": Workload(
            "primitiveMaterializedProjection",
            "ReferenceBenchmarks.primitiveScan",
            primitive,
            rows,
            ("id",),
        ),
        "fusedFilterProjectArithmetic": Workload(
            "fusedFilterProjectArithmetic",
            "ReferenceBenchmarks.fusedFilterProjectArithmetic",
            fused,
            rows - rows // 2,
            ("id", "next"),
        ),
        "groupedLowCardinalitySumOnly": Workload(
            "groupedLowCardinalitySumOnly",
            "ReferenceBenchmarks.groupedLowCardinalitySumOnly",
            grouped_sum,
            min(16, rows),
            ("group", "sum"),
        ),
        "groupedLowCardinality": Workload(
            "groupedLowCardinality",
            "ReferenceBenchmarks.groupedLowCardinality",
            grouped_full,
            min(16, rows),
            ("group", "n", "sum", "mean", "variancePop"),
            exact_checksum=False,
        ),
        "joinOneToOne": Workload(
            "joinOneToOne",
            "ReferenceBenchmarks.joinOneToOne",
            lambda: join(right_one),
            rows,
            ("key", "leftValue", "rightKey", "rightValue"),
        ),
        "joinOneToMany": Workload(
            "joinOneToMany",
            "ReferenceBenchmarks.joinOneToMany",
            lambda: join(right_many),
            rows,
            ("key", "leftValue", "rightKey", "rightValue"),
        ),
        "joinSparse": Workload(
            "joinSparse",
            "ReferenceBenchmarks.joinSparse",
            lambda: join(right_sparse),
            (rows + 9) // 10,
            ("key", "leftValue", "rightKey", "rightValue"),
        ),
        "joinSkewed": Workload(
            "joinSkewed",
            "ReferenceBenchmarks.joinSkewed",
            lambda: join(right_skew),
            min(rows, 1000),
            ("key", "leftValue", "rightKey", "rightValue"),
        ),
        "distinctLowCardinality": Workload(
            "distinctLowCardinality",
            "ReferenceBenchmarks.distinctLowCardinality",
            distinct,
            (rows + 2) // 3,
            ("rightKey",),
        ),
        "semiJoinSparse": Workload(
            "semiJoinSparse",
            "ReferenceBenchmarks.semiJoinSparse",
            semi,
            (rows + 9) // 10,
            ("key", "leftValue"),
        ),
        "antiJoinSparse": Workload(
            "antiJoinSparse",
            "ReferenceBenchmarks.antiJoinSparse",
            anti,
            rows - (rows + 9) // 10,
            ("key", "leftValue"),
        ),
        "unionAll": Workload(
            "unionAll",
            "ReferenceBenchmarks.unionAll",
            union,
            rows * 2,
            ("key", "leftValue"),
        ),
    }
    return data, workloads


def read_oracle(path: Path) -> dict[str, tuple[int, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        rows = csv.DictReader(handle, delimiter="\t")
        return {
            row["benchmark"]: (int(row["output_rows"]), row["checksum"])
            for row in rows
        }


def read_frame4s_times(path: Path | None) -> dict[str, float]:
    if path is None:
        return {}
    content = json.loads(path.read_text(encoding="utf-8"))
    times: dict[str, float] = {}
    for result in content:
        benchmark = result["benchmark"]
        if ".ColumnarBenchmarks." not in benchmark or result["mode"] != "avgt":
            continue
        name = benchmark.rsplit(".", 1)[-1]
        metric = result["primaryMetric"]
        score = float(metric["score"])
        unit = metric["scoreUnit"]
        if unit == "us/op":
            score /= 1000.0
        elif unit == "ns/op":
            score /= 1_000_000.0
        elif unit != "ms/op":
            continue
        times[name] = score
    return times


def measure(
    operation: Callable[[], pd.DataFrame], target_seconds: float, samples: int
) -> tuple[int, list[float]]:
    for _ in range(20):
        operation()
    timer = timeit.Timer(operation)
    number = 1
    while number < 8192 and timer.timeit(number=number) < target_seconds:
        number *= 2
    number = min(number, 8192)
    gc.collect()
    values = [seconds / number for seconds in timer.repeat(samples, number)]
    return number, values


def write_receipt(
    receipt: Path,
    args: argparse.Namespace,
    workloads: dict[str, Workload],
    oracle: dict[str, tuple[int, str]],
    frame4s_times: dict[str, float],
) -> None:
    raw = receipt / "raw"
    raw.mkdir(parents=True, exist_ok=True)
    target = 0.05 if args.quick else 0.25
    sample_count = 3 if args.quick else 9
    validation_rows: list[dict[str, object]] = []
    timing_rows: list[dict[str, object]] = []

    for workload in workloads.values():
        output = workload.run()
        actual_checksum = checksum(output)
        oracle_rows, oracle_checksum = oracle[workload.oracle]
        actual_columns = tuple(str(column) for column in output.columns)
        if len(output) != workload.expected_rows or len(output) != oracle_rows:
            raise RuntimeError(
                f"{workload.name}: output rows {len(output)} disagree with "
                f"{workload.expected_rows}/{oracle_rows}"
            )
        if actual_columns != workload.expected_columns:
            raise RuntimeError(
                f"{workload.name}: output columns {actual_columns!r} disagree with "
                f"{workload.expected_columns!r}"
            )
        status = "row-count-and-schema"
        if workload.exact_checksum:
            if actual_checksum != oracle_checksum:
                raise RuntimeError(
                    f"{workload.name}: checksum {actual_checksum} disagrees with "
                    f"{workload.oracle}={oracle_checksum}"
                )
            status = "exact-checksum"
        validation_rows.append(
            {
                "benchmark": f"PandasBenchmarks.{workload.name}",
                "oracle": workload.oracle,
                "output_rows": len(output),
                "checksum": actual_checksum,
                "status": status,
            }
        )
        loops, samples = measure(workload.run, target, sample_count)
        median_ms = statistics.median(samples) * 1000.0
        frame4s_name = workload.oracle.rsplit(".", 1)[-1]
        frame4s_ms = frame4s_times.get(frame4s_name)
        timing_rows.append(
            {
                "benchmark": workload.name,
                "loops_per_sample": loops,
                "samples": sample_count,
                "median_ms": median_ms,
                "min_ms": min(samples) * 1000.0,
                "max_ms": max(samples) * 1000.0,
                "frame4s_ms": frame4s_ms,
                "pandas_over_frame4s": (
                    median_ms / frame4s_ms if frame4s_ms is not None else None
                ),
            }
        )

    with (raw / "timings.csv").open("w", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=timing_rows[0].keys())
        writer.writeheader()
        writer.writerows(timing_rows)
    with (receipt / "validation.tsv").open(
        "w", newline="", encoding="utf-8"
    ) as handle:
        writer = csv.DictWriter(
            handle, fieldnames=validation_rows[0].keys(), delimiter="\t"
        )
        writer.writeheader()
        writer.writerows(validation_rows)

    environment = {
        "receipt_format": "1",
        "suite": "frame4s-pandas-comparison-court",
        "quick": str(args.quick).lower(),
        "rows": str(args.rows),
        "python.version": platform.python_version(),
        "pandas.version": pd.__version__,
        "numpy.version": np.__version__,
        "os": platform.platform(),
        "machine": platform.machine(),
        "processor": platform.processor() or "unrecorded",
        "threads": "1",
        "timing": f"timeit,{sample_count}-sample-median,target={target}s",
        "oracle.validation": str(args.oracle_validation),
        "frame4s.jmh": str(args.frame4s_jmh or "not-provided"),
    }
    (receipt / "environment.properties").write_text(
        "".join(f"{key}={value}\n" for key, value in environment.items()),
        encoding="utf-8",
    )

    lines = [
        "# Pandas comparison court",
        "",
        (
            "Quick wiring receipt; timings are provisional."
            if args.quick
            else "Full separate-process Pandas timing receipt."
        ),
        "",
        "Pandas runs eagerly in a pinned single-thread Python process. It is not invoked",
        "through JMH, and Python allocation is not compared with JVM GC allocation.",
        "",
        "| Workload | Pandas median | Range | frame4s JMH | Pandas/frame4s |",
        "|---|---:|---:|---:|---:|",
    ]
    for row in timing_rows:
        frame4s = row["frame4s_ms"]
        ratio = row["pandas_over_frame4s"]
        lines.append(
            f"| `{row['benchmark']}` | {row['median_ms']:.6f} ms | "
            f"{row['min_ms']:.6f}–{row['max_ms']:.6f} ms | "
            + (f"{frame4s:.6f} ms | {ratio:.2f}x |" if frame4s else "n/a | n/a |")
        )
    lines.extend(
        [
            "",
            "Exact checksum comparisons include primitive materialized projection, fused",
            "filter/project, grouped sum, joins, distinct, semi/anti join, and unionAll.",
            "The four-stat grouped workload is row/schema validated but not raw-bit ranked",
            "because Pandas and frame4s use different floating moment algorithms.",
            "",
            "Raw per-sample aggregates are in `raw/timings.csv`; output validation is in",
            "`validation.tsv`; runtime provenance is in `environment.properties`.",
            "",
        ]
    )
    (receipt / "summary.md").write_text("\n".join(lines), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--rows", type=int, default=1000)
    parser.add_argument("--oracle-validation", type=Path, required=True)
    parser.add_argument("--frame4s-jmh", type=Path)
    parser.add_argument("--quick", action="store_true")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    if args.rows <= 0:
        raise SystemExit("--rows must be positive")
    if np.__version__ != EXPECTED_NUMPY or pd.__version__ != EXPECTED_PANDAS:
        raise RuntimeError(
            "Pandas court dependency mismatch: "
            f"numpy={np.__version__} (expected {EXPECTED_NUMPY}), "
            f"pandas={pd.__version__} (expected {EXPECTED_PANDAS})"
        )
    _, workloads = fixtures(args.rows)
    oracle = read_oracle(args.oracle_validation)
    missing = sorted(
        workload.oracle
        for workload in workloads.values()
        if workload.oracle not in oracle
    )
    if missing:
        raise RuntimeError(f"oracle validation is missing: {', '.join(missing)}")
    frame4s_times = read_frame4s_times(args.frame4s_jmh)
    write_receipt(args.receipt, args, workloads, oracle, frame4s_times)


if __name__ == "__main__":
    main()
