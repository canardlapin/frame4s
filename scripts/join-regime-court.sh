#!/usr/bin/env bash
# Full deterministic frame4s/pandas/Polars size-by-key-order court.
set -euo pipefail

receipt="${1:-docs/benchmarks/receipts/$(date +%F)-join-regimes}"
mode="${2:-quick}"
sizes="${FRAME4S_JOIN_REGIME_SIZES:-1000,4000,16000,64000,256000,1000000,4000000}"
orders="sorted,right-shuffled,both-shuffled"
heap="${FRAME4S_SCALE_HEAP:-8g}"

if [[ "$receipt" != /* ]]; then
  receipt="$(pwd)/$receipt"
fi

quick_flag=""
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

sbt "benchmarks/Jmh/runMain frame4s.benchmarks.JoinRegimeCourtRunner --receipt $receipt/frame4s --sizes $sizes --orders $orders --heap $heap $quick_flag"

uv run --with 'numpy==2.5.1' --with 'pandas==3.0.1' \
  python scripts/join-regime-court.py \
  --receipt "$receipt/pandas" \
  --backend pandas \
  --sizes "$sizes" \
  --orders "$orders" \
  $quick_flag

POLARS_MAX_THREADS=1 uv run --with 'numpy==2.5.1' --with 'polars==1.43.1' \
  python scripts/join-regime-court.py \
  --receipt "$receipt/polars-pinned" \
  --backend polars \
  --expected-threads 1 \
  --sizes "$sizes" \
  --orders "$orders" \
  $quick_flag

env -u POLARS_MAX_THREADS uv run --with 'numpy==2.5.1' --with 'polars==1.43.1' \
  python scripts/join-regime-court.py \
  --receipt "$receipt/polars-default" \
  --backend polars \
  --sizes "$sizes" \
  --orders "$orders" \
  $quick_flag

python3 scripts/join-regime-summary.py \
  --receipt "$receipt" \
  --frame4s "$receipt/frame4s" \
  --pandas "$receipt/pandas" \
  --polars-pinned "$receipt/polars-pinned" \
  --polars-default "$receipt/polars-default"
