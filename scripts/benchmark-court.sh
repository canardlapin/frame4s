#!/usr/bin/env bash
set -euo pipefail

receipt="${1:-docs/benchmarks/receipts/$(date +%F)-local}"
mode="${2:-quick}"

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

arguments=(--receipt "$receipt" --rows "${FRAME4S_BENCHMARK_ROWS:-1000}")
if [[ "$mode" == "quick" ]]; then
  arguments+=(--quick)
elif [[ "$mode" != "full" ]]; then
  echo "usage: $0 [receipt-directory] [quick|full]" >&2
  exit 2
fi

quoted_arguments="$(printf ' %q' "${arguments[@]}")"
sbt "benchmarks/Jmh/runMain frame4s.benchmarks.CourtRunner${quoted_arguments}"
