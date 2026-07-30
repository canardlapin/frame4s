#!/usr/bin/env python3
"""Combine frame4s and pinned-Polars join-shape regime receipts."""

from __future__ import annotations

import argparse
import csv
from pathlib import Path


def read(
    path: Path,
) -> dict[tuple[int, str, str], dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return {
            (int(row["rows"]), row["workload"], row["order"]): row
            for row in csv.DictReader(handle, delimiter="\t")
        }


def write_summary(args: argparse.Namespace) -> None:
    frame_metrics = read(args.frame4s / "metrics.tsv")
    polars_metrics = read(args.polars / "metrics.tsv")
    frame_validation = read(args.frame4s / "validation.tsv")
    polars_validation = read(args.polars / "validation.tsv")
    keys = set(frame_metrics)
    for name, values in {
        "polars metrics": polars_metrics,
        "frame4s validation": frame_validation,
        "polars validation": polars_validation,
    }.items():
        if set(values) != keys:
            raise RuntimeError(f"{name} does not cover the frame4s matrix")
    for key in keys:
        frame = frame_validation[key]
        polars = polars_validation[key]
        for field in (
            "output_rows",
            "left_keys_sha256",
            "right_keys_sha256",
            "right_values_sha256",
        ):
            if frame[field] != polars[field]:
                raise RuntimeError(
                    f"{key} differs for {field}: "
                    f"{frame[field]} != {polars[field]}"
                )

    combined: list[dict[str, object]] = []
    for key in sorted(keys):
        frame_ms = float(frame_metrics[key]["milliseconds"])
        polars_ms = float(polars_metrics[key]["milliseconds"])
        combined.append(
            {
                "rows": key[0],
                "workload": key[1],
                "order": key[2],
                "frame4s_ms": frame_ms,
                "polars_1t_ms": polars_ms,
                "frame4s_over_polars": frame_ms / polars_ms,
                "frame4s_allocation_bytes": float(
                    frame_metrics[key]["allocation_bytes"]
                ),
                "passes_1_05": str(frame_ms / polars_ms <= 1.05).lower(),
            }
        )
    with (args.receipt / "combined.tsv").open(
        "w", newline="", encoding="utf-8"
    ) as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=combined[0].keys(),
            delimiter="\t",
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerows(combined)

    lines = [
        "# Sparse and existential join regime court",
        "",
        "frame4s and Polars use identical deterministic fixtures. Ratios at or",
        "below 1.05 pass the pinned single-thread target.",
        "",
        "| Rows | Workload | Order | frame4s | Polars 1t | Ratio | Allocation | Gate |",
        "|---:|---|---|---:|---:|---:|---:|---|",
    ]
    for row in combined:
        lines.append(
            f"| {row['rows']} | {row['workload']} | {row['order']} | "
            f"{row['frame4s_ms']:.3f} ms | {row['polars_1t_ms']:.3f} ms | "
            f"{row['frame4s_over_polars']:.2f}x | "
            f"{row['frame4s_allocation_bytes'] / 1_000_000.0:.2f} MB | "
            f"{'pass' if row['passes_1_05'] == 'true' else 'miss'} |"
        )
    misses = [row for row in combined if row["passes_1_05"] == "false"]
    lines.extend(
        [
            "",
            (
                "Every measured regime passes."
                if not misses
                else f"{len(misses)} measured regime(s) remain above 1.05x."
            ),
            "",
            "Component receipts retain raw samples, versions, thread counts, exact",
            "frame4s checksums, comparator invariants, and matching fixture hashes.",
            "",
        ]
    )
    (args.receipt / "summary.md").write_text(
        "\n".join(lines), encoding="utf-8"
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--frame4s", type=Path, required=True)
    parser.add_argument("--polars", type=Path, required=True)
    args = parser.parse_args()
    args.receipt.mkdir(parents=True, exist_ok=True)
    return args


if __name__ == "__main__":
    write_summary(parse_args())
