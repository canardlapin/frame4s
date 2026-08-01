#!/usr/bin/env python3
"""Compare an R5k join candidate with the frozen anti-fitting baseline."""

from __future__ import annotations

import argparse
import csv
from dataclasses import dataclass
from pathlib import Path


MetricKey = tuple[int, str, str]
ValidationKey = tuple[int, str]


@dataclass(frozen=True)
class Metric:
    milliseconds: float
    allocation: float


@dataclass(frozen=True)
class Gate:
    name: str
    passed: bool
    observed: str
    limit: str
    detail: str


def tsv_rows(path: Path) -> list[dict[str, str]]:
    with path.open(newline="", encoding="utf-8") as handle:
        return list(csv.DictReader(handle, delimiter="\t"))


def metrics(receipt: Path) -> dict[MetricKey, Metric]:
    output: dict[MetricKey, Metric] = {}
    for component in ("main", "scale"):
        for row in tsv_rows(receipt / component / "metrics.tsv"):
            key = (int(row["rows"]), row["workload"], row["endpoint"])
            if key in output:
                raise RuntimeError(f"duplicate metric {key}")
            output[key] = Metric(
                milliseconds=float(row["milliseconds"]),
                allocation=float(row["allocation_bytes"]),
            )
    return output


def validations(receipt: Path) -> dict[ValidationKey, dict[str, str]]:
    output: dict[ValidationKey, dict[str, str]] = {}
    for component in ("main", "scale"):
        for row in tsv_rows(receipt / component / "validation.tsv"):
            key = (int(row["rows"]), row["workload"])
            if key in output:
                raise RuntimeError(f"duplicate validation {key}")
            output[key] = row
    return output


def key_text(key: MetricKey) -> str:
    return f"{key[0]}/{key[1]}/{key[2]}"


def maximum_ratio(
    baseline: dict[MetricKey, Metric],
    candidate: dict[MetricKey, Metric],
    selected: list[MetricKey],
    field: str,
) -> tuple[float, MetricKey]:
    if not selected:
        raise RuntimeError(f"no cells selected for {field} gate")
    ratios = [
        (getattr(candidate[key], field) / getattr(baseline[key], field), key)
        for key in selected
    ]
    return max(ratios, key=lambda value: value[0])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    arguments = parser.parse_args()

    baseline_metrics = metrics(arguments.baseline)
    candidate_metrics = metrics(arguments.candidate)
    if baseline_metrics.keys() != candidate_metrics.keys():
        missing = sorted(baseline_metrics.keys() - candidate_metrics.keys())
        extra = sorted(candidate_metrics.keys() - baseline_metrics.keys())
        raise RuntimeError(f"metric cells differ: missing={missing} extra={extra}")

    baseline_validations = validations(arguments.baseline)
    candidate_validations = validations(arguments.candidate)
    if baseline_validations.keys() != candidate_validations.keys():
        raise RuntimeError("validation cells differ")

    semantic_fields = (
        "output_rows",
        "checksum",
        "strategy",
        "left_keys_sha256",
        "right_keys_sha256",
    )
    semantic_failures: list[str] = []
    for key in sorted(baseline_validations):
        before = baseline_validations[key]
        after = candidate_validations[key]
        for field in semantic_fields:
            if before[field] != after[field]:
                semantic_failures.append(
                    f"{key[0]}/{key[1]} {field}: {before[field]} != {after[field]}"
                )

    gates: list[Gate] = [
        Gate(
            "semantic identity",
            not semantic_failures,
            "0 mismatches" if not semantic_failures else f"{len(semantic_failures)} mismatches",
            "0 mismatches",
            "; ".join(semantic_failures[:3]) or "all exact validation fields match",
        )
    ]

    all_keys = sorted(baseline_metrics)
    small = [key for key in all_keys if key[0] <= 16000]
    small_ratio, small_worst = maximum_ratio(
        baseline_metrics, candidate_metrics, small, "milliseconds"
    )
    gates.append(
        Gate(
            "small-tier latency",
            small_ratio <= 1.05,
            f"{small_ratio:.4f}x",
            "<=1.05x",
            key_text(small_worst),
        )
    )

    large_shuffled = [
        key
        for key in all_keys
        if key[0] >= 64000 and key[1] == "fully-shuffled"
    ]
    shuffled_ratio, shuffled_worst = maximum_ratio(
        baseline_metrics, candidate_metrics, large_shuffled, "milliseconds"
    )
    gates.append(
        Gate(
            "large shuffled latency",
            shuffled_ratio <= 1.03,
            f"{shuffled_ratio:.4f}x",
            "<=1.03x",
            key_text(shuffled_worst),
        )
    )

    fallback = [
        key
        for key in all_keys
        if key[1] in {"fully-shuffled", "late-inversion", "nullable-key"}
    ]
    fallback_ratio, fallback_worst = maximum_ratio(
        baseline_metrics, candidate_metrics, fallback, "allocation"
    )
    gates.append(
        Gate(
            "fallback allocation",
            fallback_ratio <= 1.05,
            f"{fallback_ratio:.4f}x",
            "<=1.05x",
            key_text(fallback_worst),
        )
    )

    for rows in (1_000_000, 4_000_000):
        key = (rows, "aligned-unique", "deep-materialized")
        speedup = (
            baseline_metrics[key].milliseconds / candidate_metrics[key].milliseconds
        )
        gates.append(
            Gate(
                f"{rows // 1_000_000}M aligned deep speedup",
                speedup >= 1.50,
                f"{speedup:.4f}x",
                ">=1.50x",
                key_text(key),
            )
        )

        gather_key = (rows, "aligned-unique", "gather-view")
        incremental = (
            candidate_metrics[key].allocation
            - candidate_metrics[gather_key].allocation
        )
        useful = rows * (4 + 8 + 4 + 8)
        ratio = incremental / useful
        gates.append(
            Gate(
                f"{rows // 1_000_000}M physical-output allocation",
                ratio <= 1.10,
                f"{incremental / 1_000_000:.3f} MB ({ratio:.4f}x payload)",
                "<=1.10x useful payload",
                key_text(key),
            )
        )

    run_key = (4_000_000, "aligned-unique", "gather-view")
    run_ratio = (
        candidate_metrics[run_key].allocation / baseline_metrics[run_key].allocation
    )
    gates.append(
        Gate(
            "4M run-selection allocation",
            run_ratio <= 0.80,
            f"{run_ratio:.4f}x",
            "<=0.80x",
            key_text(run_key),
        )
    )

    comparison_rows: list[list[str]] = []
    for key in all_keys:
        before = baseline_metrics[key]
        after = candidate_metrics[key]
        comparison_rows.append(
            [
                str(key[0]),
                key[1],
                key[2],
                f"{before.milliseconds:.9f}",
                f"{after.milliseconds:.9f}",
                f"{after.milliseconds / before.milliseconds:.6f}",
                f"{before.allocation:.3f}",
                f"{after.allocation:.3f}",
                f"{after.allocation / before.allocation:.6f}",
            ]
        )

    with (arguments.candidate / "comparison.tsv").open(
        "w", newline="", encoding="utf-8"
    ) as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(
            [
                "rows",
                "workload",
                "endpoint",
                "baseline_ms",
                "candidate_ms",
                "time_ratio",
                "baseline_allocation_bytes",
                "candidate_allocation_bytes",
                "allocation_ratio",
            ]
        )
        writer.writerows(comparison_rows)

    with (arguments.candidate / "gates.tsv").open(
        "w", newline="", encoding="utf-8"
    ) as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(["gate", "status", "observed", "limit", "detail"])
        for gate in gates:
            writer.writerow(
                [
                    gate.name,
                    "PASS" if gate.passed else "FAIL",
                    gate.observed,
                    gate.limit,
                    gate.detail,
                ]
            )

    status = "PASS" if all(gate.passed for gate in gates) else "FAIL"
    markdown = [
        "# R5k.3 anti-fitting comparison",
        "",
        f"Overall status: **{status}**.",
        "",
        "| Gate | Status | Observed | Limit | Detail |",
        "|---|---|---:|---:|---|",
        *[
            f"| {gate.name} | {'PASS' if gate.passed else 'FAIL'} | "
            f"{gate.observed} | {gate.limit} | {gate.detail} |"
            for gate in gates
        ],
        "",
        "`comparison.tsv` retains every baseline and candidate timing and allocation ratio.",
        "",
    ]
    (arguments.candidate / "comparison.md").write_text(
        "\n".join(markdown), encoding="utf-8"
    )

    return 0 if status == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
