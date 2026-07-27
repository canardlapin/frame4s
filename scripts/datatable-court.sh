#!/usr/bin/env bash
set -euo pipefail

export LC_ALL=C
export OMP_NUM_THREADS=1
export OPENBLAS_NUM_THREADS=1
export MKL_NUM_THREADS=1
export VECLIB_MAXIMUM_THREADS=1

exec Rscript scripts/datatable-court.R "$@"
