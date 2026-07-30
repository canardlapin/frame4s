#!/usr/bin/env python3
"""Aggregate interleaved matched-endpoint join court rounds."""

from __future__ import annotations

import argparse
import csv
import statistics
from pathlib import Path


BACKENDS = ("frame4s", "pandas", "polars-pinned", "polars-default")
COMPARABLE_ENDPOINTS = ("deep-materialized", "matched-consumption")
VALIDATION_FIELDS = (
    "output_rows",
    "schema",
    "ordered_output_sha256",
    "column_sum",
    "left_keys_sha256",
    "right_keys_sha256",
)


def read_tsv(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle, delimiter="\t"))


def read_properties(path: Path) -> dict[str, str]:
    output: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line and not line.startswith("#"):
            key, value = line.split("=", 1)
            output[key] = value
    return output


def key(row: dict[str, str]) -> tuple[int, str]:
    return int(row["rows"]), row["order"]


def metric_key(row: dict[str, str]) -> tuple[int, str, str]:
    return int(row["rows"]), row["order"], row["endpoint"]


def validate_rounds(
    receipt: Path, rounds: int
) -> tuple[
    dict[tuple[int, str], dict[str, str]],
    dict[tuple[str, int, int, str, str], float],
    list[dict[str, object]],
]:
    canonical: dict[tuple[int, str], dict[str, str]] = {}
    metrics: dict[tuple[str, int, int, str, str], float] = {}
    process_rows: list[dict[str, object]] = []
    expected_keys: set[tuple[int, str]] | None = None

    for process_round in range(1, rounds + 1):
        round_dir = receipt / f"round-{process_round}"
        for backend in BACKENDS:
            component = round_dir / backend
            environment = read_properties(
                component / "environment.properties"
            )
            actual_round = int(environment["process_round"])
            if actual_round != process_round:
                raise RuntimeError(
                    f"{component} reports process round {actual_round}"
                )
            process_rows.append(
                {
                    "round": process_round,
                    "backend": backend,
                    "sequence_position": int(
                        environment["sequence_position"]
                    ),
                    "version": environment.get(
                        "backend.version",
                        environment.get("java.version", "unrecorded"),
                    ),
                    "threads": environment.get(
                        "threads", environment.get("processors", "unrecorded")
                    ),
                }
            )

            validation = {
                key(row): row
                for row in read_tsv(component / "validation.tsv")
            }
            if expected_keys is None:
                expected_keys = set(validation)
            elif set(validation) != expected_keys:
                raise RuntimeError(
                    f"{component} validation matrix differs from prior components"
                )
            for validation_key, row in validation.items():
                if validation_key not in canonical:
                    canonical[validation_key] = row
                else:
                    expected = canonical[validation_key]
                    for field in VALIDATION_FIELDS:
                        if row[field] != expected[field]:
                            raise RuntimeError(
                                f"{component} differs at {validation_key}/{field}: "
                                f"{row[field]} != {expected[field]}"
                            )

            component_metrics = read_tsv(component / "metrics.tsv")
            for row in component_metrics:
                rows, order, endpoint = metric_key(row)
                value_key = (
                    backend,
                    process_round,
                    rows,
                    order,
                    endpoint,
                )
                if value_key in metrics:
                    raise RuntimeError(f"duplicate metric {value_key}")
                metrics[value_key] = float(row["milliseconds"])

    if expected_keys is None:
        raise RuntimeError("no validation rows found")
    for process_round in range(1, rounds + 1):
        for rows, order in expected_keys:
            for backend in BACKENDS:
                endpoints = (
                    ("prepare", "gather-view", *COMPARABLE_ENDPOINTS)
                    if backend == "frame4s"
                    else COMPARABLE_ENDPOINTS
                )
                for endpoint in endpoints:
                    value_key = (
                        backend,
                        process_round,
                        rows,
                        order,
                        endpoint,
                    )
                    if value_key not in metrics:
                        raise RuntimeError(f"missing metric {value_key}")
    return canonical, metrics, process_rows


def describe(values: list[float]) -> tuple[float, float, float]:
    return statistics.median(values), min(values), max(values)


def write_summary(args: argparse.Namespace) -> None:
    canonical, metrics, process_rows = validate_rounds(
        args.receipt, args.rounds
    )
    keys = sorted(canonical)
    aggregate_rows: list[dict[str, object]] = []
    for rows, order in keys:
        for endpoint in COMPARABLE_ENDPOINTS:
            by_backend: dict[str, tuple[float, float, float]] = {}
            for backend in BACKENDS:
                values = [
                    metrics[(backend, process_round, rows, order, endpoint)]
                    for process_round in range(1, args.rounds + 1)
                ]
                by_backend[backend] = describe(values)
            paired_ratios: dict[str, tuple[float, float, float]] = {}
            for backend in BACKENDS[1:]:
                ratios = [
                    metrics[
                        ("frame4s", process_round, rows, order, endpoint)
                    ]
                    / metrics[
                        (backend, process_round, rows, order, endpoint)
                    ]
                    for process_round in range(1, args.rounds + 1)
                ]
                paired_ratios[backend] = describe(ratios)

            aggregate_rows.append(
                {
                    "rows": rows,
                    "order": order,
                    "endpoint": endpoint,
                    "frame4s_median_ms": by_backend["frame4s"][0],
                    "frame4s_min_ms": by_backend["frame4s"][1],
                    "frame4s_max_ms": by_backend["frame4s"][2],
                    "pandas_median_ms": by_backend["pandas"][0],
                    "pandas_min_ms": by_backend["pandas"][1],
                    "pandas_max_ms": by_backend["pandas"][2],
                    "polars_1t_median_ms": by_backend["polars-pinned"][0],
                    "polars_1t_min_ms": by_backend["polars-pinned"][1],
                    "polars_1t_max_ms": by_backend["polars-pinned"][2],
                    "polars_default_median_ms": by_backend[
                        "polars-default"
                    ][0],
                    "polars_default_min_ms": by_backend["polars-default"][1],
                    "polars_default_max_ms": by_backend["polars-default"][2],
                    "f4s_over_pandas_median": paired_ratios["pandas"][0],
                    "f4s_over_pandas_min": paired_ratios["pandas"][1],
                    "f4s_over_pandas_max": paired_ratios["pandas"][2],
                    "f4s_over_polars_1t_median": paired_ratios[
                        "polars-pinned"
                    ][0],
                    "f4s_over_polars_1t_min": paired_ratios[
                        "polars-pinned"
                    ][1],
                    "f4s_over_polars_1t_max": paired_ratios[
                        "polars-pinned"
                    ][2],
                    "f4s_over_polars_default_median": paired_ratios[
                        "polars-default"
                    ][0],
                    "f4s_over_polars_default_min": paired_ratios[
                        "polars-default"
                    ][1],
                    "f4s_over_polars_default_max": paired_ratios[
                        "polars-default"
                    ][2],
                }
            )

    with (args.receipt / "combined.tsv").open(
        "w", newline="", encoding="utf-8"
    ) as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=aggregate_rows[0].keys(),
            delimiter="\t",
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerows(aggregate_rows)

    diagnostics: list[dict[str, object]] = []
    for rows, order in keys:
        for endpoint in ("prepare", "gather-view"):
            values = [
                metrics[
                    ("frame4s", process_round, rows, order, endpoint)
                ]
                for process_round in range(1, args.rounds + 1)
            ]
            median, minimum, maximum = describe(values)
            diagnostics.append(
                {
                    "rows": rows,
                    "order": order,
                    "endpoint": endpoint,
                    "median_ms": median,
                    "minimum_ms": minimum,
                    "maximum_ms": maximum,
                }
            )
    with (args.receipt / "frame4s-diagnostics.tsv").open(
        "w", newline="", encoding="utf-8"
    ) as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=diagnostics[0].keys(),
            delimiter="\t",
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerows(diagnostics)

    allocation_rows: list[dict[str, object]] = []
    for rows, order in keys:
        for endpoint in (
            "prepare",
            "gather-view",
            *COMPARABLE_ENDPOINTS,
        ):
            values = []
            for process_round in range(1, args.rounds + 1):
                frame_metrics = {
                    metric_key(row): row
                    for row in read_tsv(
                        args.receipt
                        / f"round-{process_round}"
                        / "frame4s"
                        / "metrics.tsv"
                    )
                }
                values.append(
                    float(
                        frame_metrics[
                            (rows, order, endpoint)
                        ]["allocation_bytes"]
                    )
                )
            median, minimum, maximum = describe(values)
            allocation_rows.append(
                {
                    "rows": rows,
                    "order": order,
                    "endpoint": endpoint,
                    "median_bytes": median,
                    "minimum_bytes": minimum,
                    "maximum_bytes": maximum,
                }
            )
    with (args.receipt / "frame4s-allocation.tsv").open(
        "w", newline="", encoding="utf-8"
    ) as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=allocation_rows[0].keys(),
            delimiter="\t",
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerows(allocation_rows)

    with (args.receipt / "process-order.tsv").open(
        "w", newline="", encoding="utf-8"
    ) as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=process_rows[0].keys(),
            delimiter="\t",
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerows(
            sorted(
                process_rows,
                key=lambda row: (
                    int(row["round"]),
                    int(row["sequence_position"]),
                ),
            )
        )

    validation_rows = [
        {
            "rows": rows,
            "order": order,
            **{
                field: canonical[(rows, order)][field]
                for field in VALIDATION_FIELDS
            },
        }
        for rows, order in keys
    ]
    with (args.receipt / "validation.tsv").open(
        "w", newline="", encoding="utf-8"
    ) as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=validation_rows[0].keys(),
            delimiter="\t",
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerows(validation_rows)

    lines = [
        "# Matched join endpoint court",
        "",
        f"Results are medians across {args.rounds} interleaved process-level rounds.",
        "Parenthesized ranges are the minimum and maximum round medians. Ratios are",
        "paired within each process round before aggregation.",
        "",
        "Every backend returns `key: Int32`, `leftValue: Int64`, `rightKey: Int32`,",
        "and `rightValue: Int64` in exact stable-left order. Input construction is",
        "outside timing. `deep-materialized` is the only construction comparator;",
        "`matched-consumption` additionally sums all four materialized columns.",
        "",
        "| Rows | Order | Endpoint | frame4s ms | pandas ms | Polars 1t ms | Polars default ms | f4s/pandas | f4s/Polars 1t | f4s/Polars default |",
        "|---:|---|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for row in aggregate_rows:
        lines.append(
            f"| {row['rows']} | {row['order']} | {row['endpoint']} | "
            f"{row['frame4s_median_ms']:.3f} "
            f"({row['frame4s_min_ms']:.3f}–{row['frame4s_max_ms']:.3f}) | "
            f"{row['pandas_median_ms']:.3f} "
            f"({row['pandas_min_ms']:.3f}–{row['pandas_max_ms']:.3f}) | "
            f"{row['polars_1t_median_ms']:.3f} "
            f"({row['polars_1t_min_ms']:.3f}–{row['polars_1t_max_ms']:.3f}) | "
            f"{row['polars_default_median_ms']:.3f} "
            f"({row['polars_default_min_ms']:.3f}–{row['polars_default_max_ms']:.3f}) | "
            f"{row['f4s_over_pandas_median']:.2f}x | "
            f"{row['f4s_over_polars_1t_median']:.2f}x | "
            f"{row['f4s_over_polars_default_median']:.2f}x |"
        )
    lines.extend(
        [
            "",
            "## frame4s-only diagnostics",
            "",
            "`gather-view` is the old internal endpoint and has no pandas or Polars",
            "ratio. `prepare` isolates normalization and physical classification.",
            "",
            "| Rows | Order | Endpoint | Median ms | Process range |",
            "|---:|---|---|---:|---:|",
        ]
    )
    for row in diagnostics:
        lines.append(
            f"| {row['rows']} | {row['order']} | {row['endpoint']} | "
            f"{row['median_ms']:.3f} | "
            f"{row['minimum_ms']:.3f}–{row['maximum_ms']:.3f} |"
        )
    lines.extend(
        [
            "",
            "## frame4s allocation",
            "",
            "Allocation is the median normalized JMH allocation across process",
            "rounds. The process range remains visible because deep materialization",
            "can expose fork-specific escape-analysis and garbage-collection effects.",
            "",
            "| Rows | Order | Endpoint | Median MB/op | Process range MB/op |",
            "|---:|---|---|---:|---:|",
        ]
    )
    for row in allocation_rows:
        lines.append(
            f"| {row['rows']} | {row['order']} | {row['endpoint']} | "
            f"{row['median_bytes'] / 1_000_000.0:.2f} | "
            f"{row['minimum_bytes'] / 1_000_000.0:.2f}–"
            f"{row['maximum_bytes'] / 1_000_000.0:.2f} |"
        )
    lines.extend(
        [
            "",
            "`validation.tsv` proves identical schema, cardinality, exact ordered",
            "column bytes, full-column sum, and input fingerprints. Component",
            "directories retain raw samples, allocation, versions, thread counts,",
            "stage attribution, and sequence position.",
            "",
        ]
    )
    (args.receipt / "summary.md").write_text(
        "\n".join(lines), encoding="utf-8"
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--rounds", type=int, default=3)
    args = parser.parse_args()
    if args.rounds < 3:
        raise SystemExit("--rounds must be at least 3")
    return args


if __name__ == "__main__":
    write_summary(parse_args())
