#!/usr/bin/env python3
"""Combine frame4s and pinned/default-Polars join-shape regime receipts."""

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
    polars_pinned_metrics = read(args.polars_pinned / "metrics.tsv")
    polars_default_metrics = read(args.polars_default / "metrics.tsv")
    frame_validation = read(args.frame4s / "validation.tsv")
    polars_pinned_validation = read(args.polars_pinned / "validation.tsv")
    polars_default_validation = read(args.polars_default / "validation.tsv")
    keys = set(frame_metrics)
    for name, values in {
        "pinned Polars metrics": polars_pinned_metrics,
        "default Polars metrics": polars_default_metrics,
        "frame4s validation": frame_validation,
        "pinned Polars validation": polars_pinned_validation,
        "default Polars validation": polars_default_validation,
    }.items():
        if set(values) != keys:
            raise RuntimeError(f"{name} does not cover the frame4s matrix")
    for key in keys:
        frame = frame_validation[key]
        for name, polars in (
            ("pinned Polars", polars_pinned_validation[key]),
            ("default Polars", polars_default_validation[key]),
        ):
            for field in (
                "output_rows",
                "left_keys_sha256",
                "right_keys_sha256",
                "right_values_sha256",
            ):
                if frame[field] != polars[field]:
                    raise RuntimeError(
                        f"{key} differs for {name} {field}: "
                        f"{frame[field]} != {polars[field]}"
                    )

    combined: list[dict[str, object]] = []
    for key in sorted(keys):
        frame_ms = float(frame_metrics[key]["milliseconds"])
        pinned_ms = float(polars_pinned_metrics[key]["milliseconds"])
        default_ms = float(polars_default_metrics[key]["milliseconds"])
        combined.append(
            {
                "rows": key[0],
                "workload": key[1],
                "order": key[2],
                "frame4s_ms": frame_ms,
                "polars_1t_ms": pinned_ms,
                "polars_default_ms": default_ms,
                "frame4s_over_polars_1t": frame_ms / pinned_ms,
                "frame4s_over_polars_default": frame_ms / default_ms,
                "frame4s_allocation_bytes": float(
                    frame_metrics[key]["allocation_bytes"]
                ),
                "passes_1_05": str(frame_ms / pinned_ms <= 1.05).lower(),
                "passes_default_2_0": str(frame_ms / default_ms <= 2.0).lower(),
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
        "| Rows | Workload | Order | frame4s | Polars 1t | Polars default | f4s/Polars 1t | f4s/Polars default | Allocation | Gates |",
        "|---:|---|---|---:|---:|---:|---:|---:|---:|---|",
    ]
    for row in combined:
        lines.append(
            f"| {row['rows']} | {row['workload']} | {row['order']} | "
            f"{row['frame4s_ms']:.3f} ms | {row['polars_1t_ms']:.3f} ms | "
            f"{row['polars_default_ms']:.3f} ms | "
            f"{row['frame4s_over_polars_1t']:.2f}x | "
            f"{row['frame4s_over_polars_default']:.2f}x | "
            f"{row['frame4s_allocation_bytes'] / 1_000_000.0:.2f} MB | "
            f"{'1t-pass' if row['passes_1_05'] == 'true' else '1t-miss'}, "
            f"{'default-pass' if row['passes_default_2_0'] == 'true' else 'default-miss'} |"
        )
    misses = [row for row in combined if row["passes_1_05"] == "false"]
    default_misses = [
        row for row in combined if row["passes_default_2_0"] == "false"
    ]
    lines.extend(
        [
            "",
            (
                "Every measured regime passes."
                if not misses
                else f"{len(misses)} measured regime(s) remain above 1.05x."
            ),
            (
                "Every measured regime is within 2.0x default Polars."
                if not default_misses
                else f"{len(default_misses)} measured regime(s) remain above 2.0x default Polars."
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
    parser.add_argument("--polars-pinned", type=Path, required=True)
    parser.add_argument("--polars-default", type=Path, required=True)
    args = parser.parse_args()
    args.receipt.mkdir(parents=True, exist_ok=True)
    return args


if __name__ == "__main__":
    write_summary(parse_args())
