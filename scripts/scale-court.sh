#!/usr/bin/env bash
# Large-fixture court for the columnar candidate.
#
# The ratified 1,000-row court in scripts/benchmark-court.sh is unchanged and remains
# the oracle record. This script adds the tier where pandas and Polars stop being
# dominated by their own dispatch overhead, which is the only regime in which a
# comparative claim against them means anything.
#
# Only the candidate runs here. The semantic reference join is a nested-loop cross
# product, so it cannot execute at these sizes; the receipt states that limit rather
# than hiding it.
#
#   scripts/scale-court.sh docs/benchmarks/receipts/YYYY-MM-DD-scale-1m full 1000000
set -euo pipefail

receipt="${1:-docs/benchmarks/receipts/$(date +%F)-scale}"
mode="${2:-quick}"
rows="${3:-1000000}"
heap="${FRAME4S_SCALE_HEAP:-8g}"

if [[ "$receipt" != /* ]]; then
  receipt="$(pwd)/$receipt"
fi

if command -v sysctl >/dev/null 2>&1; then
  hardware="$(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m)"
elif command -v lscpu >/dev/null 2>&1; then
  hardware="$(lscpu | sed -n 's/^Model name:[[:space:]]*//p' | head -1)"
else
  hardware="$(uname -m)"
fi

export FRAME4S_HARDWARE="$hardware"

arguments=(--receipt "$receipt" --rows "$rows" --tier scale --heap "$heap")
if [[ "$mode" == "quick" ]]; then
  arguments+=(--quick)
elif [[ "$mode" != "full" ]]; then
  echo "usage: $0 [receipt-directory] [quick|full] [rows]" >&2
  exit 2
fi

quoted_arguments="$(printf ' %q' "${arguments[@]}")"
sbt "benchmarks/Jmh/runMain frame4s.benchmarks.CourtRunner${quoted_arguments}"
