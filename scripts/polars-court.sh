#!/usr/bin/env bash
# Separate-process Polars comparison court.
#
# Polars is measured twice on purpose. Pass --threads 1 for the pinned
# apples-to-apples kernel comparison against single-threaded frame4s, and omit it
# for the default thread pool a user actually gets. A ratio published without a
# stated thread count is not a claim; both receipts belong in the record.
#
#   scripts/polars-court.sh \
#     --receipt docs/benchmarks/receipts/YYYY-MM-DD-polars/pinned \
#     --threads 1 \
#     --rows 1000000 \
#     --oracle-validation docs/benchmarks/receipts/YYYY-MM-DD-jvm/validation.tsv \
#     --frame4s-jmh docs/benchmarks/receipts/YYYY-MM-DD-jvm/raw/jmh.json
set -euo pipefail

# Keep every numeric backend except Polars itself single-threaded so the thread
# count under study is Polars' own pool and nothing else.
export OMP_NUM_THREADS=1
export OPENBLAS_NUM_THREADS=1
export MKL_NUM_THREADS=1
export VECLIB_MAXIMUM_THREADS=1
export NUMEXPR_NUM_THREADS=1

forwarded=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --threads)
      if [[ $# -lt 2 ]]; then
        echo "--threads requires a value" >&2
        exit 2
      fi
      export POLARS_MAX_THREADS="$2"
      shift 2
      ;;
    *)
      forwarded+=("$1")
      shift
      ;;
  esac
done

exec "${FRAME4S_PYTHON:-python3}" scripts/polars-court.py "${forwarded[@]}"
