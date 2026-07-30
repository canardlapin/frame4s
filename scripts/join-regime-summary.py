#!/usr/bin/env python3
"""Combine frame4s, pandas, and Polars join-regime receipts."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path

PARITY_TOLERANCE = 0.05


def read_metrics(path: Path) -> dict[tuple[int, str], dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return {
            (int(row["rows"]), row["order"]): row
            for row in csv.DictReader(handle, delimiter="\t")
        }


def read_validations(path: Path) -> dict[tuple[int, str], dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return {
            (int(row["rows"]), row["order"]): row
            for row in csv.DictReader(handle, delimiter="\t")
        }


def validate_fixtures(
    named: dict[str, dict[tuple[int, str], dict[str, str]]]
) -> list[tuple[int, str]]:
    key_sets = {name: set(values) for name, values in named.items()}
    expected = next(iter(key_sets.values()))
    for name, keys in key_sets.items():
        if keys != expected:
            raise RuntimeError(
                f"{name} fixture keys differ: missing={expected - keys}, extra={keys - expected}"
            )
    for key in expected:
        left = {name: rows[key]["left_keys_sha256"] for name, rows in named.items()}
        right = {
            name: rows[key]["right_keys_sha256"] for name, rows in named.items()
        }
        if len(set(left.values())) != 1 or len(set(right.values())) != 1:
            raise RuntimeError(
                f"{key} permutation fingerprints differ: left={left}, right={right}"
            )
        output_rows = {name: rows[key]["output_rows"] for name, rows in named.items()}
        if len(set(output_rows.values())) != 1:
            raise RuntimeError(f"{key} output cardinalities differ: {output_rows}")
    return sorted(expected)


def per_row(metric: dict[str, str]) -> float:
    if "nanoseconds_per_row" in metric:
        return float(metric["nanoseconds_per_row"])
    return float(metric["milliseconds"]) * 1_000_000.0 / int(metric["rows"])


def relative_status(ratio: float) -> str:
    if ratio < 1.0 - PARITY_TOLERANCE:
        return "frame4s-faster"
    if ratio > 1.0 + PARITY_TOLERANCE:
        return "comparator-faster"
    return "within-5-percent"


def write_summary(args: argparse.Namespace) -> None:
    frame = read_metrics(args.frame4s / "metrics.tsv")
    pandas = read_metrics(args.pandas / "metrics.tsv")
    polars_pinned = read_metrics(args.polars_pinned / "metrics.tsv")
    polars_default = read_metrics(args.polars_default / "metrics.tsv")
    keys = validate_fixtures(
        {
            "frame4s": read_validations(args.frame4s / "validation.tsv"),
            "pandas": read_validations(args.pandas / "validation.tsv"),
            "polars-pinned": read_validations(
                args.polars_pinned / "validation.tsv"
            ),
            "polars-default": read_validations(
                args.polars_default / "validation.tsv"
            ),
        }
    )
    for name, values in {
        "frame4s": frame,
        "pandas": pandas,
        "polars-pinned": polars_pinned,
        "polars-default": polars_default,
    }.items():
        if set(values) != set(keys):
            raise RuntimeError(f"{name} metrics do not cover the validation matrix")

    combined: list[dict[str, object]] = []
    for rows, order in keys:
        key = rows, order
        frame_ms = float(frame[key]["milliseconds"])
        pandas_ms = float(pandas[key]["milliseconds"])
        pinned_ms = float(polars_pinned[key]["milliseconds"])
        default_ms = float(polars_default[key]["milliseconds"])
        combined.append(
            {
                "rows": rows,
                "order": order,
                "frame4s_ms": frame_ms,
                "pandas_ms": pandas_ms,
                "polars_pinned_ms": pinned_ms,
                "polars_default_ms": default_ms,
                "frame4s_over_pandas": frame_ms / pandas_ms,
                "frame4s_over_polars_pinned": frame_ms / pinned_ms,
                "frame4s_over_polars_default": frame_ms / default_ms,
            }
        )

    with (args.receipt / "combined.tsv").open(
        "w", newline="", encoding="utf-8"
    ) as handle:
        writer = csv.DictWriter(
            handle, fieldnames=combined[0].keys(), delimiter="\t", lineterminator="\n"
        )
        writer.writeheader()
        writer.writerows(combined)

    comparisons = {
        "pandas": "frame4s_over_pandas",
        "polars-pinned": "frame4s_over_polars_pinned",
        "polars-default": "frame4s_over_polars_default",
    }
    relative_regimes: list[dict[str, object]] = []
    orders = sorted({order for _, order in keys})
    for comparison, ratio_field in comparisons.items():
        for order in orders:
            ordered_rows = sorted(
                (
                    row
                    for row in combined
                    if row["order"] == order
                ),
                key=lambda row: int(row["rows"]),
            )
            segment: list[dict[str, object]] = []
            for row in ordered_rows:
                status = relative_status(float(row[ratio_field]))
                if segment and relative_status(
                    float(segment[-1][ratio_field])
                ) != status:
                    relative_regimes.append(
                        {
                            "comparison": comparison,
                            "order": order,
                            "first_rows": segment[0]["rows"],
                            "last_rows": segment[-1]["rows"],
                            "status": relative_status(
                                float(segment[-1][ratio_field])
                            ),
                            "minimum_ratio": min(
                                float(value[ratio_field]) for value in segment
                            ),
                            "maximum_ratio": max(
                                float(value[ratio_field]) for value in segment
                            ),
                        }
                    )
                    segment = []
                segment.append(row)
            if segment:
                relative_regimes.append(
                    {
                        "comparison": comparison,
                        "order": order,
                        "first_rows": segment[0]["rows"],
                        "last_rows": segment[-1]["rows"],
                        "status": relative_status(
                            float(segment[-1][ratio_field])
                        ),
                        "minimum_ratio": min(
                            float(value[ratio_field]) for value in segment
                        ),
                        "maximum_ratio": max(
                            float(value[ratio_field]) for value in segment
                        ),
                    }
                )
    with (args.receipt / "relative-regimes.tsv").open(
        "w", newline="", encoding="utf-8"
    ) as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=relative_regimes[0].keys(),
            delimiter="\t",
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerows(relative_regimes)

    implementations = {
        "frame4s": frame,
        "pandas": pandas,
        "polars-pinned": polars_pinned,
        "polars-default": polars_default,
    }
    crossover_rows: list[dict[str, object]] = []
    for implementation, metrics in implementations.items():
        for order in orders:
            ordered = sorted(
                (
                    (rows, metrics[(rows, order)])
                    for rows, candidate_order in keys
                    if candidate_order == order
                ),
                key=lambda value: value[0],
            )
            for (lower_rows, lower), (upper_rows, upper) in zip(
                ordered, ordered[1:]
            ):
                ratio = per_row(upper) / per_row(lower)
                crossover_rows.append(
                    {
                        "implementation": implementation,
                        "order": order,
                        "lower_rows": lower_rows,
                        "upper_rows": upper_rows,
                        "per_row_cost_ratio": ratio,
                        "candidate": str(ratio >= 1.25).lower(),
                    }
                )
    with (args.receipt / "crossover-candidates.tsv").open(
        "w", newline="", encoding="utf-8"
    ) as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=(
                "implementation",
                "order",
                "lower_rows",
                "upper_rows",
                "per_row_cost_ratio",
                "candidate",
            ),
            delimiter="\t",
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerows(crossover_rows)

    lines = [
        "# Join size and key-order regime court",
        "",
        "All four timing paths use identical deterministic key/value multisets.",
        "Ratios below 1.00 favor frame4s. Polars is reported both pinned to one",
        "thread and at its default pool.",
        "",
        "| Rows | Key order | frame4s | pandas | Polars 1t | Polars default | f4s/pandas | f4s/Polars 1t | f4s/Polars default |",
        "|---:|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for row in combined:
        lines.append(
            f"| {row['rows']} | {row['order']} | {row['frame4s_ms']:.3f} ms | "
            f"{row['pandas_ms']:.3f} ms | {row['polars_pinned_ms']:.3f} ms | "
            f"{row['polars_default_ms']:.3f} ms | "
            f"{row['frame4s_over_pandas']:.2f}x | "
            f"{row['frame4s_over_polars_pinned']:.2f}x | "
            f"{row['frame4s_over_polars_default']:.2f}x |"
        )
    lines.extend(
        [
            "",
            "## Relative regimes",
            "",
            "These descriptive ranges use a ±5% tie band. They locate observed",
            "winner changes; they do not admit an optimization by themselves.",
            "",
        ]
    )
    for row in relative_regimes:
        rows_label = (
            f"{row['first_rows']}"
            if row["first_rows"] == row["last_rows"]
            else f"{row['first_rows']}–{row['last_rows']}"
        )
        lines.append(
            f"- {row['comparison']} / {row['order']} / {rows_label} rows: "
            f"{row['status']} "
            f"({row['minimum_ratio']:.2f}–{row['maximum_ratio']:.2f}x)."
        )
    candidates = [
        row for row in crossover_rows if row["candidate"] == "true"
    ]
    lines.extend(
        [
            "",
            "## Candidate crossover intervals",
            "",
            (
                "Adjacent intervals are listed when per-row cost rises by at least 25%."
                if candidates
                else "No adjacent interval increased per-row cost by at least 25%."
            ),
            "",
        ]
    )
    for row in candidates:
        lines.append(
            f"- {row['implementation']} / {row['order']}: "
            f"{row['lower_rows']}–{row['upper_rows']} rows "
            f"({row['per_row_cost_ratio']:.2f}x per-row cost)."
        )
    lines.extend(
        [
            "",
            "`combined.tsv` contains the exact ratios. Component receipts retain",
            "individual samples, allocation, stage attribution, validation invariants,",
            "versions, thread counts, and deterministic permutation fingerprints.",
            "",
        ]
    )
    (args.receipt / "summary.md").write_text("\n".join(lines), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--frame4s", type=Path, required=True)
    parser.add_argument("--pandas", type=Path, required=True)
    parser.add_argument("--polars-pinned", type=Path, required=True)
    parser.add_argument("--polars-default", type=Path, required=True)
    arguments = parser.parse_args()
    arguments.receipt.mkdir(parents=True, exist_ok=True)
    return arguments


if __name__ == "__main__":
    write_summary(parse_args())
