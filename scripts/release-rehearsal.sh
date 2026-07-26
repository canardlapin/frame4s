#!/usr/bin/env bash
set -euo pipefail

source_root="$(pwd)"
candidate_commit="$(git rev-parse HEAD)"
version="${FRAME4S_REHEARSAL_VERSION:-0.1.0-RC1}"
receipt="${1:-docs/benchmarks/receipts/$(date +%F)-r6-release-rehearsal}"
if [[ "$receipt" != /* ]]; then
  receipt="$source_root/$receipt"
fi
mkdir -p "$receipt"

repository="$(mktemp -d /tmp/frame4s-release-rehearsal.XXXXXX)"
gpg_home="$(mktemp -d /tmp/frame4s-release-signing.XXXXXX)"
workspace="$(mktemp -d /tmp/frame4s-release-workspace.XXXXXX)"
chmod 700 "$gpg_home"
cleanup() {
  rm -rf "$repository" "$gpg_home" "$workspace"
}
trap cleanup EXIT
export SBT_OPTS="${SBT_OPTS:-} -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"

{
  java_command="${JAVA_HOME:+$JAVA_HOME/bin/}java"
  echo "java.version=$("$java_command" -version 2>&1 | head -1)"
  echo "node.version=$(node --version)"
  echo "sbt.version=$(sed -n 's/^sbt.version=//p' project/build.properties)"
  echo "scala.version=3.7.4"
  echo "scalajs.version=1.22.0"
  echo "candidate.version=$version"
  echo "repository=isolated temporary Maven repository"
  echo "commit=$candidate_commit"
  if git diff --quiet && git diff --cached --quiet; then
    echo "worktree=clean"
  else
    echo "worktree=dirty"
  fi
} >"$receipt/environment.properties"

{
  printf '%s\n' build.sbt project/build.properties project/plugins.sbt
  find \
    modules/core \
    modules/fs2 \
    modules/staged-consumer-jvm \
    modules/staged-consumer-js \
    -path '*/src/main/*' \
    -type f \
    -print
} |
  LC_ALL=C sort |
  while IFS= read -r source; do
    sha256sum "$source"
  done >"$receipt/source-files.sha256"

rsync -a \
  --exclude .git \
  --exclude .mote \
  --exclude target \
  --exclude vendor \
  --exclude docs/benchmarks/receipts \
  "$source_root/" \
  "$workspace/"
git -C "$workspace" init --quiet
git -C "$workspace" add -A
git -C "$workspace" \
  -c user.name=frame4s-rehearsal \
  -c user.email=release-rehearsal@frame4s.invalid \
  commit --quiet --no-gpg-sign -m "release rehearsal snapshot"
cd "$workspace"

sbt \
  -Dframe4s.rehearsal.enabled=true \
  -Dframe4s.rehearsal.repo="$repository" \
  "set ThisBuild / version := \"$version\"" \
  "set coreJVM / publishTo := Some(Resolver.file(\"frame4s-rehearsal-publish\", file(\"$repository\"))(Resolver.mavenStylePatterns))" \
  "set coreJS / publishTo := Some(Resolver.file(\"frame4s-rehearsal-publish\", file(\"$repository\"))(Resolver.mavenStylePatterns))" \
  "set fs2JVM / publishTo := Some(Resolver.file(\"frame4s-rehearsal-publish\", file(\"$repository\"))(Resolver.mavenStylePatterns))" \
  "set fs2JS / publishTo := Some(Resolver.file(\"frame4s-rehearsal-publish\", file(\"$repository\"))(Resolver.mavenStylePatterns))" \
  coreJVM/publish \
  coreJS/publish \
  fs2JVM/publish \
  fs2JS/publish \
  stagedConsumerJVM/run \
  stagedConsumerJS/run \
  2>&1 |
  sed \
    -e "s|$repository|<rehearsal-repository>|g" \
    -e "s|$source_root|<workspace>|g" \
    -e "s|$workspace|<workspace>|g" |
  tee "$receipt/publish-and-consumer-output.txt"

rehearsal_uid="frame4s release rehearsal <rehearsal@frame4s.invalid>"
GNUPGHOME="$gpg_home" gpg \
  --batch \
  --pinentry-mode loopback \
  --passphrase '' \
  --quick-generate-key "$rehearsal_uid" ed25519 sign 1d \
  >/dev/null 2>&1
fingerprint="$(
  GNUPGHOME="$gpg_home" gpg --batch --with-colons --list-secret-keys "$rehearsal_uid" |
    awk -F: '$1 == "fpr" { print $10; exit }'
)"
test -n "$fingerprint"

find "$repository/io/github/canardlapin" -type f \
  \( -name '*.pom' -o -name '*.jar' \) -print0 |
  while IFS= read -r -d '' artifact; do
    GNUPGHOME="$gpg_home" gpg \
      --batch \
      --armor \
      --detach-sign \
      --local-user "$fingerprint" \
      "$artifact" \
      >/dev/null 2>&1
    GNUPGHOME="$gpg_home" gpg \
      --batch \
      --verify "$artifact.asc" "$artifact" \
      >/dev/null 2>&1
    hash="$(sha256sum "$artifact" | awk '{ print $1 }')"
    printf '%s\n' "$hash" >"$artifact.sha256"
  done

artifacts=(
  frame4s-core_3
  frame4s-core_sjs1_3
  frame4s-fs2_3
  frame4s-fs2_sjs1_3
)

for artifact in "${artifacts[@]}"; do
  directory="$repository/io/github/canardlapin/$artifact/$version"
  test -f "$directory/$artifact-$version.pom"
  test -f "$directory/$artifact-$version.jar"
  test -f "$directory/$artifact-$version-sources.jar"
  test -f "$directory/$artifact-$version-javadoc.jar"
  for published in \
    "$directory/$artifact-$version.pom" \
    "$directory/$artifact-$version.jar" \
    "$directory/$artifact-$version-sources.jar" \
    "$directory/$artifact-$version-javadoc.jar"; do
    test -f "$published.asc"
    test -f "$published.sha256"
    GNUPGHOME="$gpg_home" gpg --batch --verify "$published.asc" "$published" >/dev/null 2>&1
    expected="$(cat "$published.sha256")"
    actual="$(sha256sum "$published" | awk '{ print $1 }')"
    test "$expected" = "$actual"
  done
  grep -F '<name>' "$directory/$artifact-$version.pom"
  grep -F '<description>' "$directory/$artifact-$version.pom"
  grep -F '<url>https://github.com/canardlapin/frame4s</url>' "$directory/$artifact-$version.pom"
  grep -F '<licenses>' "$directory/$artifact-$version.pom"
  grep -F '<developers>' "$directory/$artifact-$version.pom"
  grep -F '<scm>' "$directory/$artifact-$version.pom"
  if grep -R -F --include='*.pom' -- '-SNAPSHOT' "$directory"; then
    echo "snapshot dependency found in $artifact" >&2
    exit 1
  fi
  for archive in "$directory"/*.jar; do
    if jar tf "$archive" | rg '(^|/)(vendor|\.mote|target)(/|$)'; then
      echo "non-publishable path found in $archive" >&2
      exit 1
    fi
  done
  if jar tf "$directory/$artifact-$version-javadoc.jar" |
    rg '(^|/)examples(/|\.html$)'; then
    echo "excluded example page found in $artifact documentation jar" >&2
    exit 1
  fi
done >"$receipt/artifact-verification.txt"

find "$repository/io/github/canardlapin" -type f -print |
  sed "s|^$repository/||" |
  LC_ALL=C sort >"$receipt/artifact-list.txt"

while IFS= read -r relative; do
  hash="$(sha256sum "$repository/$relative" | awk '{ print $1 }')"
  printf '%s  %s\n' "$hash" "$relative"
done <"$receipt/artifact-list.txt" >"$receipt/artifact-sha256.txt"

{
  echo "signature.status=ephemeral-rehearsal-pass"
  echo "signature.algorithm=ed25519"
  echo "signature.public.fingerprint=$fingerprint"
  echo "production.signature.status=not-exercised"
  echo "production.reason=owner signing secret is intentionally absent from the local court"
  echo "required.next=owner runs the offline signing and credential-recovery procedure before R7"
} >"$receipt/signing-status.properties"
