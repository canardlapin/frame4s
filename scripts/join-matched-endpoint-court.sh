#!/usr/bin/env bash
# Interleaved, process-replicated matched join endpoint court.
set -euo pipefail

receipt="${1:-docs/benchmarks/receipts/$(date +%F)-r5k1-matched-endpoints}"
mode="${2:-quick}"
rounds="${FRAME4S_MATCHED_ROUNDS:-3}"
orders="${FRAME4S_MATCHED_ORDERS:-sorted,right-shuffled,both-shuffled}"
resume="${FRAME4S_MATCHED_RESUME:-0}"

if [[ "$mode" == "quick" ]]; then
  sizes="${FRAME4S_MATCHED_SIZES:-16000}"
  heap="${FRAME4S_MATCHED_HEAP:-1g}"
  quick_flag="--quick"
elif [[ "$mode" == "full" ]]; then
  sizes="${FRAME4S_MATCHED_SIZES:-1000000,4000000}"
  heap="${FRAME4S_MATCHED_HEAP:-4g}"
  quick_flag=""
else
  echo "usage: $0 [receipt-directory] [quick|full]" >&2
  exit 2
fi

if (( rounds < 3 )); then
  echo "FRAME4S_MATCHED_ROUNDS must be at least 3" >&2
  exit 2
fi

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
export OMP_NUM_THREADS=1
export OPENBLAS_NUM_THREADS=1
export MKL_NUM_THREADS=1
export VECLIB_MAXIMUM_THREADS=1
export NUMEXPR_NUM_THREADS=1

component_complete() {
  local component="$1"
  [[ "$resume" == "1" ]] &&
    [[ -f "$component/environment.properties" ]] &&
    [[ -f "$component/metrics.tsv" ]] &&
    [[ -f "$component/validation.tsv" ]]
}

run_frame4s() {
  local process_round="$1"
  local sequence_position="$2"
  local component="$receipt/round-$process_round/frame4s"
  if component_complete "$component"; then
    echo "resume: keeping $component"
    return
  fi
  sbt "benchmarks/Jmh/runMain frame4s.benchmarks.JoinMatchedEndpointCourtRunner --receipt $component --sizes $sizes --orders $orders --heap $heap --round $process_round --sequence-position $sequence_position $quick_flag"
}

run_pandas() {
  local process_round="$1"
  local sequence_position="$2"
  local component="$receipt/round-$process_round/pandas"
  if component_complete "$component"; then
    echo "resume: keeping $component"
    return
  fi
  uv run --with 'numpy==2.5.1' --with 'pandas==3.0.1' \
    python scripts/join-matched-endpoint-court.py \
    --receipt "$component" \
    --backend pandas \
    --sizes "$sizes" \
    --orders "$orders" \
    --process-round "$process_round" \
    --sequence-position "$sequence_position" \
    $quick_flag
}

# polars==1.43.1 was yanked from PyPI after the R5k.1 receipt, so uv can no
# longer resolve it. FRAME4S_POLARS_PYTHON names an interpreter that already
# has numpy==2.5.1 and polars==1.43.1 installed (pip accepts the yanked
# release under an exact pin). The runner records the versions it imports, so
# a substituted interpreter cannot silently change the comparator.
if [[ -n "${FRAME4S_POLARS_PYTHON:-}" ]]; then
  polars_python=("$FRAME4S_POLARS_PYTHON")
else
  polars_python=(uv run --with 'numpy==2.5.1' --with 'polars==1.43.1' python)
fi

run_polars_pinned() {
  local process_round="$1"
  local sequence_position="$2"
  local component="$receipt/round-$process_round/polars-pinned"
  if component_complete "$component"; then
    echo "resume: keeping $component"
    return
  fi
  POLARS_MAX_THREADS=1 "${polars_python[@]}" \
    scripts/join-matched-endpoint-court.py \
    --receipt "$component" \
    --backend polars \
    --expected-threads 1 \
    --sizes "$sizes" \
    --orders "$orders" \
    --process-round "$process_round" \
    --sequence-position "$sequence_position" \
    $quick_flag
}

run_polars_default() {
  local process_round="$1"
  local sequence_position="$2"
  local component="$receipt/round-$process_round/polars-default"
  if component_complete "$component"; then
    echo "resume: keeping $component"
    return
  fi
  env -u POLARS_MAX_THREADS "${polars_python[@]}" \
    scripts/join-matched-endpoint-court.py \
    --receipt "$component" \
    --backend polars \
    --sizes "$sizes" \
    --orders "$orders" \
    --process-round "$process_round" \
    --sequence-position "$sequence_position" \
    $quick_flag
}

process_round=1
while (( process_round <= rounds )); do
  rotation=$(( (process_round - 1) % 4 ))
  case "$rotation" in
    0)
      run_frame4s "$process_round" 1
      run_pandas "$process_round" 2
      run_polars_pinned "$process_round" 3
      run_polars_default "$process_round" 4
      ;;
    1)
      run_pandas "$process_round" 1
      run_polars_pinned "$process_round" 2
      run_polars_default "$process_round" 3
      run_frame4s "$process_round" 4
      ;;
    2)
      run_polars_pinned "$process_round" 1
      run_polars_default "$process_round" 2
      run_frame4s "$process_round" 3
      run_pandas "$process_round" 4
      ;;
    3)
      run_polars_default "$process_round" 1
      run_frame4s "$process_round" 2
      run_pandas "$process_round" 3
      run_polars_pinned "$process_round" 4
      ;;
  esac
  process_round=$(( process_round + 1 ))
done

python3 scripts/join-matched-endpoint-summary.py \
  --receipt "$receipt" \
  --rounds "$rounds"
