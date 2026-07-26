#!/usr/bin/env bash
set -euo pipefail

receipt="${1:-docs/benchmarks/receipts/$(date +%F)-r4-small-algebra}"
if [[ "$receipt" != /* ]]; then
  receipt="$(pwd)/$receipt"
fi
mkdir -p "$receipt"
export SBT_OPTS="${SBT_OPTS:-} -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
workspace_path="$(pwd)"
user_root="${HOME:-}"

sanitize_receipt_paths() {
  for output in "$@"; do
    sed -i.bak \
      -e "s|$workspace_path|<workspace>|g" \
      -e "s|$user_root|<user-home>|g" \
      "$output"
    rm -f "$output.bak"
  done
}

{
  java_command="${JAVA_HOME:+$JAVA_HOME/bin/}java"
  echo "java.version=$("$java_command" -version 2>&1 | head -1)"
  echo "sbt.version=$(sed -n 's/^sbt.version=//p' project/build.properties)"
  echo "scala.version=3.7.4"
  echo "os=$(uname -sr)"
  echo "arch=$(uname -m)"
  echo "commit=$(git rev-parse HEAD)"
  if git diff --quiet && git diff --cached --quiet; then
    echo "worktree=clean"
  else
    echo "worktree=dirty"
  fi
} >"$receipt/environment.properties"

shasum -a 256 \
  docs/design/architecture.md \
  docs/operations.md \
  modules/core/shared/src/main/scala/frame4s/Expression.scala \
  modules/core/shared/src/main/scala/frame4s/Frame.scala \
  modules/core/shared/src/main/scala/frame4s/LogicalPlan.scala \
  modules/core/shared/src/main/scala/frame4s/Normalization.scala \
  modules/core/shared/src/main/scala/frame4s/ReferenceInterpreter.scala \
  modules/core/shared/src/main/scala/frame4s/Schema.scala \
  modules/core/shared/src/test/scala/frame4s/SmallAlgebraSuite.scala \
  modules/testkit/shared/src/test/scala/frame4s/testkit/ConformanceLawsSuite.scala \
  modules/fs2/shared/src/test/scala/frame4s/fs2/FrameRuntimeSuite.scala \
  >"$receipt/source-files.sha256"

{
  echo "Constraint-escape audit"
  echo
  echo "Suppression/failure markers (expected: none):"
  rg -n '@nowarn|Wconf|\.fail\b|TODO|FIXME' \
    modules/core/shared/src/main/scala \
    modules/fs2/shared/src/main/scala \
    modules/testkit/shared/src/main/scala \
    || true
  echo
  echo "Runtime casts (expected: one documented named-tuple representation cast):"
  rg -n 'asInstanceOf' \
    modules/core/shared/src/main/scala \
    modules/fs2/shared/src/main/scala \
    modules/testkit/shared/src/main/scala \
    || true
  echo
  echo "Ambiguous population aggregate entry points (expected: none):"
  rg -n 'Aggregate\.variance\b|DynamicAggregate\.variance\b' \
    modules/core/shared/src/main \
    modules/fs2/shared/src/main \
    modules/testkit/shared/src/main \
    --glob '*.scala' \
    || true
} >"$receipt/type-discipline.txt"

/usr/bin/time -p -o "$receipt/gate-timing.txt" \
  sbt compileAll testAll benchmarkSmoke \
  2>&1 | tee "$receipt/gate-output.txt"

sanitize_receipt_paths "$receipt/gate-output.txt"
