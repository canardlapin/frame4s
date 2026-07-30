#!/usr/bin/env bash
# Sparse and existential join court across adjacent sizes and shuffled keys.
set -euo pipefail

receipt="${1:-docs/benchmarks/receipts/$(date +%F)-join-shape-regimes}"
mode="${2:-quick}"
sizes="${FRAME4S_JOIN_SHAPE_SIZES:-256000,1000000,4000000}"
workloads="join-sparse,join-skewed,semi-sparse,anti-sparse"
orders="sorted,both-shuffled"
heap="${FRAME4S_SCALE_HEAP:-8g}"
quick_flag=""

if [[ "$receipt" != /* ]]; then
  receipt="$(pwd)/$receipt"
fi
if [[ "$mode" == "quick" ]]; then
  quick_flag="--quick"
elif [[ "$mode" != "full" ]]; then
  echo "usage: $0 [receipt-directory] [quick|full]" >&2
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
export OMP_NUM_THREADS=1
export OPENBLAS_NUM_THREADS=1
export MKL_NUM_THREADS=1
export VECLIB_MAXIMUM_THREADS=1
export NUMEXPR_NUM_THREADS=1

sbt "benchmarks/Jmh/runMain frame4s.benchmarks.JoinShapeRegimeCourtRunner --receipt $receipt/frame4s --sizes $sizes --workloads $workloads --orders $orders --heap $heap $quick_flag"

POLARS_MAX_THREADS=1 uv run --with 'numpy==2.5.1' --with 'polars==1.43.1' \
  python scripts/join-shape-regime-court.py \
  --receipt "$receipt/polars-pinned" \
  --sizes "$sizes" \
  --workloads "$workloads" \
  --orders "$orders" \
  $quick_flag

python3 scripts/join-shape-regime-summary.py \
  --receipt "$receipt" \
  --frame4s "$receipt/frame4s" \
  --polars "$receipt/polars-pinned"
