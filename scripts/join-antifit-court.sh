#!/usr/bin/env bash
# Pre-optimization join dispatch, semantic, materialization, and allocation court.
set -euo pipefail

receipt="${1:-docs/benchmarks/receipts/$(date +%F)-r5k2-antifit-regimes}"
mode="${2:-quick}"
workloads="aligned-unique,offset-unique,interleaved-gaps,disjoint-sorted,duplicate-groups,sparse-sorted,nullable-key,multibatch-sorted,fully-shuffled,late-inversion"
scale_workloads="aligned-unique,offset-unique,interleaved-gaps,disjoint-sorted,sparse-sorted,nullable-key,multibatch-sorted,fully-shuffled,late-inversion"

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

if [[ "$mode" == "quick" ]]; then
  sizes="${FRAME4S_ANTIFIT_QUICK_SIZES:-1000,64000}"
  quick_workloads="${FRAME4S_ANTIFIT_QUICK_WORKLOADS:-aligned-unique,duplicate-groups,nullable-key,multibatch-sorted,fully-shuffled,late-inversion}"
  heap="${FRAME4S_ANTIFIT_HEAP:-2g}"
  sbt "benchmarks/Jmh/runMain frame4s.benchmarks.JoinAntiFitCourtRunner --receipt $receipt/main --sizes $sizes --workloads $quick_workloads --heap $heap --quick"
elif [[ "$mode" == "full" ]]; then
  sizes="${FRAME4S_ANTIFIT_SIZES:-1000,4000,16000,64000,256000,1000000}"
  scale_sizes="${FRAME4S_ANTIFIT_SCALE_SIZES:-4000000}"
  heap="${FRAME4S_ANTIFIT_HEAP:-8g}"
  sbt "benchmarks/Jmh/runMain frame4s.benchmarks.JoinAntiFitCourtRunner --receipt $receipt/main --sizes $sizes --workloads $workloads --heap $heap"
  sbt "benchmarks/Jmh/runMain frame4s.benchmarks.JoinAntiFitCourtRunner --receipt $receipt/scale --sizes $scale_sizes --workloads $scale_workloads --heap $heap"
else
  echo "usage: $0 [receipt-directory] [quick|full]" >&2
  exit 2
fi
