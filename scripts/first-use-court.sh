#!/usr/bin/env bash
set -euo pipefail

receipt="${1:-docs/benchmarks/receipts/$(date +%F)-first-use}"
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
  build.sbt \
  modules/core/shared/src/main/scala/frame4s/RowCodec.scala \
  modules/core/shared/src/main/scala/frame4s/Storage.scala \
  modules/fs2/shared/src/main/scala/frame4s/fs2/FrameIO.scala \
  modules/fs2/shared/src/main/scala/frame4s/fs2/FrameRuntime.scala \
  modules/fs2/jvm/src/main/scala/frame4s/fs2/PathSources.scala \
  modules/first-contact/src/main/scala/example/FirstContact.scala \
  >"$receipt/source-files.sha256"

/usr/bin/time -p -o "$receipt/timing.txt" \
  sbt 'firstContact/runMain example.FirstContact' \
  2>&1 | tee "$receipt/output.txt"

/usr/bin/time -p -o "$receipt/streaming-stress-timing.txt" \
  sbt 'fs2JVM/testOnly frame4s.fs2.FrameIOSuite' \
  2>&1 | tee "$receipt/streaming-stress-output.txt"

sanitize_receipt_paths \
  "$receipt/output.txt" \
  "$receipt/streaming-stress-output.txt"
