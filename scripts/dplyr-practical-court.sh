#!/usr/bin/env bash
set -euo pipefail

receipt="${1:-docs/benchmarks/receipts/$(date +%F)-dplyr-practical}"
mode="${2:-quick}"
rows="${FRAME4S_BENCHMARK_ROWS:-10000}"

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
export LC_ALL=C
export OMP_NUM_THREADS=1
export OPENBLAS_NUM_THREADS=1
export MKL_NUM_THREADS=1
export VECLIB_MAXIMUM_THREADS=1

frame4s_receipt="$receipt/frame4s"
dplyr_receipt="$receipt/dplyr"
arguments=(--receipt "$frame4s_receipt" --rows "$rows")
r_arguments=(
  --receipt "$dplyr_receipt"
  --rows "$rows"
  --oracle-validation "$frame4s_receipt/validation.tsv"
  --frame4s-jmh "$frame4s_receipt/raw/jmh.json"
)

if [[ "$mode" == "quick" ]]; then
  arguments+=(--quick)
  r_arguments+=(--quick)
elif [[ "$mode" != "full" ]]; then
  echo "usage: $0 [receipt-directory] [quick|full]" >&2
  exit 2
fi

quoted_arguments="$(printf ' %q' "${arguments[@]}")"
sbt "benchmarks/Jmh/runMain frame4s.benchmarks.PracticalPipelineCourtRunner${quoted_arguments}"
Rscript scripts/dplyr-practical-court.R "${r_arguments[@]}"
