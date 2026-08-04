#!/usr/bin/env bash
# Deterministic pandas/Polars key-order sensitivity court.
#
#   scripts/join-order-court.sh \
#     docs/benchmarks/receipts/YYYY-MM-DD-r5i-join-performance/order full 1000000
set -euo pipefail

receipt="${1:-docs/benchmarks/receipts/$(date +%F)-join-order}"
mode="${2:-quick}"
rows="${3:-1000000}"

arguments=(--receipt "$receipt" --rows "$rows")
if [[ "$mode" == "quick" ]]; then
  arguments+=(--quick)
elif [[ "$mode" != "full" ]]; then
  echo "usage: $0 [receipt-directory] [quick|full] [rows]" >&2
  exit 2
fi

export OMP_NUM_THREADS=1
export OPENBLAS_NUM_THREADS=1
export MKL_NUM_THREADS=1
export VECLIB_MAXIMUM_THREADS=1
export NUMEXPR_NUM_THREADS=1
export POLARS_MAX_THREADS=1

exec uv run \
  --with 'numpy==2.5.1' \
  --with 'pandas==3.0.1' \
  --with 'polars==1.43.1' \
  python scripts/join-order-court.py "${arguments[@]}"
