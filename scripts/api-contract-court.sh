#!/usr/bin/env bash
set -euo pipefail

receipt="${1:-docs/benchmarks/receipts/$(date +%F)-e1-api-contract}"
if [[ "$receipt" != /* ]]; then
  receipt="$(pwd)/$receipt"
fi

mkdir -p "$receipt"

workspace_path="$(pwd)"
user_root="${HOME:-}"
if [[ -z "${JAVA_HOME:-}" ]]; then
  echo "JAVA_HOME must name the JDK used by sbt so the receipt records the actual toolchain." >&2
  exit 2
fi
java_command="$JAVA_HOME/bin/java"

sanitize() {
  for output in "$@"; do
    sed -i.bak \
      -e "s|$workspace_path|<workspace>|g" \
      -e "s|$user_root|<user-home>|g" \
      "$output"
    rm -f "$output.bak"
  done
}

{
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
  docs/design/adr-0004-ergonomic-api.md \
  modules/core/shared/src/main/scala/frame4s/Expression.scala \
  modules/core/shared/src/main/scala/frame4s/Frame.scala \
  modules/core/shared/src/main/scala/frame4s/Schema.scala \
  modules/fs2/shared/src/main/scala/frame4s/fs2/FrameIO.scala \
  modules/fs2/shared/src/main/scala/frame4s/fs2/FrameRuntime.scala \
  modules/fs2/jvm/src/main/scala/frame4s/fs2/PathSources.scala \
  modules/first-contact/src/main/scala/example/ApiContractCourt.scala \
  modules/first-contact/src/test/scala/example/ApiContractCourtSuite.scala \
  scripts/api-contract-court.sh \
  >"$receipt/source-files.sha256"

/usr/bin/time -p -o "$receipt/timing.txt" \
  sbt \
  clean \
  firstContact/compile \
  firstContact/test \
  'coreJVM/testOnly frame4s.ErgonomicExpressionSuite frame4s.TypeDisciplineRegressionSuite' \
  'coreJS/testOnly frame4s.ErgonomicExpressionSuite frame4s.TypeDisciplineRegressionSuite' \
  'testkitJVM/testOnly frame4s.testkit.CompileTimeCourtSuite' \
  'testkitJS/testOnly frame4s.testkit.CompileTimeCourtSuite' \
  'fs2JVM/testOnly frame4s.fs2.BindingErgonomicsSuite frame4s.fs2.PathSourcesSuite' \
  'fs2JS/testOnly frame4s.fs2.BindingErgonomicsSuite' \
  2>&1 | tee "$receipt/output.txt"

sanitize "$receipt/output.txt"
