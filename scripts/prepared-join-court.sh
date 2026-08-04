#!/usr/bin/env bash
set -euo pipefail

receipt="${1:-docs/benchmarks/receipts/$(date +%F)-prepared-join-local}"
mode="${2:-quick}"
baseline="${3:-docs/benchmarks/receipts/2026-07-26-r5c-saddle-exact}"

if [[ "$receipt" != /* ]]; then
  receipt="$(pwd)/$receipt"
fi
if [[ "$baseline" != /* ]]; then
  baseline="$(pwd)/$baseline"
fi

if command -v sysctl >/dev/null 2>&1; then
  hardware="$(sysctl -n machdep.cpu.brand_string 2>/dev/null || uname -m)"
elif command -v lscpu >/dev/null 2>&1; then
  hardware="$(lscpu | sed -n 's/^Model name:[[:space:]]*//p' | head -1)"
else
  hardware="$(uname -m)"
fi

export FRAME4S_HARDWARE="$hardware"

arguments=(--receipt "$receipt" --baseline "$baseline")
if [[ "$mode" == "quick" ]]; then
  arguments+=(--quick)
elif [[ "$mode" != "full" ]]; then
  echo "usage: $0 [receipt-directory] [quick|full] [baseline-directory]" >&2
  exit 2
fi

quoted_arguments="$(printf ' %q' "${arguments[@]}")"
sbt "benchmarks/Jmh/runMain frame4s.benchmarks.PreparedJoinCourtRunner${quoted_arguments}"
