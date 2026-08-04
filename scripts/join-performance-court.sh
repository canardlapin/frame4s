#!/usr/bin/env bash
# One-shot join construction/consumption/stage court.
#
# Baseline:
#   scripts/join-performance-court.sh RECEIPT full 1000000
#
# Candidate:
#   scripts/join-performance-court.sh RECEIPT full 1000000 BASELINE
set -euo pipefail

receipt="${1:-docs/benchmarks/receipts/$(date +%F)-join-performance}"
mode="${2:-quick}"
rows="${3:-1000000}"
baseline="${4:-}"
heap="${FRAME4S_SCALE_HEAP:-8g}"

if [[ "$receipt" != /* ]]; then
  receipt="$(pwd)/$receipt"
fi

arguments=(--receipt "$receipt" --rows "$rows" --heap "$heap")
if [[ -n "$baseline" ]]; then
  if [[ "$baseline" != /* ]]; then
    baseline="$(pwd)/$baseline"
  fi
  arguments+=(--baseline "$baseline")
fi
if [[ "$mode" == "quick" ]]; then
  arguments+=(--quick)
elif [[ "$mode" != "full" ]]; then
  echo "usage: $0 [receipt] [quick|full] [rows] [baseline]" >&2
  exit 2
fi

if command -v sysctl >/dev/null 2>&1; then
  hardware="$(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m)"
elif command -v lscpu >/dev/null 2>&1; then
  hardware="$(lscpu | sed -n 's/^Model name:[[:space:]]*//p' | head -1)"
else
  hardware="$(uname -m)"
fi
export FRAME4S_HARDWARE="$hardware"

quoted_arguments="$(printf ' %q' "${arguments[@]}")"
exec sbt "benchmarks/Jmh/runMain frame4s.benchmarks.JoinPerformanceCourtRunner${quoted_arguments}"
