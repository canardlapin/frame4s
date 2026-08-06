#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'EOF'
Usage: scripts/release-owner-preflight.sh [receipt-directory]

Run from a clean checkout of the candidate commit. The default receipt
directory is target/release-owner-preflight.

Before running, the owner must set these non-secret attestations to "pass":

  FRAME4S_GITHUB_RECOVERY
  FRAME4S_CENTRAL_PORTAL_RECOVERY
  FRAME4S_PRIVATE_REPORTING_VIEW

The recovered signing key must already be available to gpg. Set its public
fingerprint in FRAME4S_SIGNING_FINGERPRINT. The script never reads or records
private-key material, recovery codes, token values, or passphrases.
EOF
}

if [[ "$#" -gt 1 ]]; then
  usage >&2
  exit 2
fi

for command in git java node sbt gpg rg jar sha256sum; do
  if ! command -v "$command" >/dev/null 2>&1; then
    echo "required command is unavailable: $command" >&2
    exit 1
  fi
done

repo_root="$(git rev-parse --show-toplevel)"
if [[ "$(pwd -P)" != "$(cd "$repo_root" && pwd -P)" ]]; then
  echo "run this script from the repository root: $repo_root" >&2
  exit 1
fi
gh_repo="$repo_root/tools/github/gh-repo"
if [[ ! -x "$gh_repo" ]]; then
  echo "required repository GitHub wrapper is unavailable: $gh_repo" >&2
  exit 1
fi
receipt="${1:-target/release-owner-preflight}"
if [[ "$receipt" != /* ]]; then
  receipt="$repo_root/$receipt"
fi
mkdir -p "$receipt"

scratch="$(mktemp -d /tmp/frame4s-owner-preflight.XXXXXX)"
current_step="initialization"
performed_by="${FRAME4S_RELEASE_PERFORMED_BY:-canardlapin}"
candidate_commit="$(git rev-parse HEAD)"
candidate_version="${FRAME4S_RELEASE_VERSION:-0.1.0}"
repository="${FRAME4S_RELEASE_REPOSITORY:-canardlapin/frame4s}"
status_file="$receipt/status.properties"

write_failure_receipt() {
  exit_status="$?"
  if [[ "$exit_status" -ne 0 ]]; then
    {
      echo "status=fail"
      echo "failed.step=$current_step"
      echo "performed.by=$performed_by"
      echo "performed.at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
      echo "candidate.commit=$candidate_commit"
      echo "candidate.version=$candidate_version"
      echo "production.publication=not-exercised"
    } >"$status_file.tmp"
    mv "$status_file.tmp" "$status_file"
    echo "preflight failed at '$current_step'; receipt: $status_file" >&2
  fi
  rm -rf "$scratch"
}
trap write_failure_receipt EXIT

fail() {
  echo "$*" >&2
  exit 1
}

require_attestation() {
  variable="$1"
  if [[ "${!variable:-}" != "pass" ]]; then
    fail "set $variable=pass only after completing its owner-only check"
  fi
}

if [[ ! "$performed_by" =~ ^[A-Za-z0-9_.-]+$ ]]; then
  fail "FRAME4S_RELEASE_PERFORMED_BY contains unsupported characters"
fi
if [[ ! "$candidate_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-RC[0-9]+)?$ ]]; then
  fail "FRAME4S_RELEASE_VERSION must be a stable x.y.z or x.y.z-RCn version"
fi
if [[ ! "$repository" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$ ]]; then
  fail "FRAME4S_RELEASE_REPOSITORY must have owner/repository form"
fi

current_step="clean-candidate"
if [[ -n "$(git status --porcelain --untracked-files=all)" ]]; then
  fail "the owner preflight requires a clean candidate checkout"
fi
if [[ -n "${FRAME4S_CANDIDATE_COMMIT:-}" ]] &&
  [[ "$FRAME4S_CANDIDATE_COMMIT" != "$candidate_commit" ]]; then
  fail "HEAD does not match FRAME4S_CANDIDATE_COMMIT"
fi

current_step="owner-attestations"
require_attestation FRAME4S_GITHUB_RECOVERY
require_attestation FRAME4S_CENTRAL_PORTAL_RECOVERY
require_attestation FRAME4S_PRIVATE_REPORTING_VIEW

current_step="supported-toolchain"
java_specification="$(
  java -XshowSettings:properties -version 2>&1 |
    awk -F= '/java.specification.version/ {
      gsub(/[[:space:]]/, "", $2)
      print $2
      exit
    }'
)"
[[ "$java_specification" == "21" ]] ||
  fail "JDK 21 is required; found Java specification $java_specification"

node_version="$(node --version)"
[[ "$node_version" =~ ^v24\. ]] ||
  fail "Node.js 24 is required; found $node_version"

normal_sbt_version="$(sed -n 's/^sbt.version=//p' project/build.properties)"
[[ "$normal_sbt_version" == "1.10.5" ]] ||
  fail "project/build.properties must pin sbt 1.10.5"

current_step="central-portal-toolchain"
toolchain_log="$scratch/central-portal-toolchain.txt"
sbt -sbt-version 1.12.11 \
  "set ThisBuild / version := \"$candidate_version\"" \
  'set ThisBuild / isSnapshot := false' \
  'show sbtVersion' \
  'show coreJVM/publishTo' \
  'inspect sonaRelease' \
  'show cireleasePublishCommand' >"$toolchain_log" 2>&1

rg -q $'\t1\\.12\\.11$' "$toolchain_log" ||
  fail "the release probe did not run on sbt 1.12.11"
rg -q 'Some\\(FileRepository\\(local-staging,' "$toolchain_log" ||
  fail "stable artifacts are not routed to Central Portal local staging"
rg -q 'Command: sonaRelease' "$toolchain_log" ||
  fail "the Central Portal release command is unavailable"
rg -q $'\t\\+publishSigned$' "$toolchain_log" ||
  fail "sbt-ci-release is not configured to publish signed artifacts"

current_step="github-administration"
"$gh_repo" auth status >/dev/null 2>&1 ||
  fail "GitHub CLI authentication is unavailable"
repository_admin="$("$gh_repo" api "repos/$repository" --jq '.permissions.admin')"
[[ "$repository_admin" == "true" ]] ||
  fail "$performed_by does not have repository administration access"

current_step="github-release-secrets"
release_secret_names="$(
  "$gh_repo" secret list \
    --repo "$repository" \
    --app actions \
    --json name \
    --jq '.[].name'
)"
required_secrets=(
  PGP_PASSPHRASE
  PGP_SECRET
  SONATYPE_USERNAME
  SONATYPE_PASSWORD
)
for secret_name in "${required_secrets[@]}"; do
  if ! printf '%s\n' "$release_secret_names" | rg -Fqx "$secret_name"; then
    fail "required GitHub Actions secret is absent: $secret_name"
  fi
done

current_step="private-vulnerability-reporting"
private_reporting="$(
  "$gh_repo" api \
    -H "Accept: application/vnd.github+json" \
    "repos/$repository/private-vulnerability-reporting" \
    --jq '.enabled'
)"
[[ "$private_reporting" == "true" ]] ||
  fail "GitHub private vulnerability reporting is not enabled"

current_step="production-signing-key"
fingerprint="$(
  printf '%s' "${FRAME4S_SIGNING_FINGERPRINT:-}" |
    tr -d '[:space:]' |
    tr '[:lower:]' '[:upper:]'
)"
if [[ ! "$fingerprint" =~ ^([[:xdigit:]]{40}|[[:xdigit:]]{64})$ ]]; then
  fail "FRAME4S_SIGNING_FINGERPRINT must be a full public fingerprint"
fi

secret_key_fingerprints="$(
  gpg --batch --with-colons --list-secret-keys "$fingerprint" 2>/dev/null |
    awk -F: '$1 == "fpr" { print toupper($10) }'
)"
printf '%s\n' "$secret_key_fingerprints" | rg -Fqx "$fingerprint" ||
  fail "the recovered gpg secret key does not match the declared fingerprint"

signature_payload="$scratch/signature-payload.txt"
signature_file="$scratch/signature-payload.txt.asc"
printf 'frame4s release owner preflight\ncommit=%s\n' \
  "$candidate_commit" >"$signature_payload"
gpg \
  --armor \
  --detach-sign \
  --local-user "$fingerprint" \
  --output "$signature_file" \
  "$signature_payload"
gpg --batch --verify "$signature_file" "$signature_payload" >/dev/null 2>&1

current_step="clean-machine-court"
sbt \
  formatCheck \
  docsCheck \
  apiDocs \
  versionPolicyCheck \
  compileAll \
  testAll \
  benchmarkSmoke

current_step="isolated-artifact-rehearsal"
FRAME4S_REHEARSAL_VERSION="$candidate_version" \
  bash scripts/release-rehearsal.sh "$receipt/artifact-rehearsal"

current_step="final-receipt"
{
  echo "status=pass"
  echo "performed.by=$performed_by"
  echo "performed.at=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "candidate.commit=$candidate_commit"
  echo "candidate.version=$candidate_version"
  echo "github.recovery=pass"
  echo "repository.admin=pass"
  echo "release.secret.names=pass"
  echo "signing.recovery=pass"
  echo "signing.public.fingerprint=$fingerprint"
  echo "detached.signature.roundtrip=pass"
  echo "central.portal.credential.recovery=pass"
  echo "central.portal.toolchain=sbt-1.12.11"
  echo "central.portal.routing=local-staging"
  echo "clean.machine.court=pass"
  echo "artifact.rehearsal=pass"
  echo "private.security.channel=pass"
  echo "private.security.channel.nonmaintainer.view=pass"
  echo "production.publication=not-exercised"
  echo "remaining.bus.factor=1"
} >"$status_file.tmp"
mv "$status_file.tmp" "$status_file"

current_step="complete"
trap - EXIT
rm -rf "$scratch"
echo "owner preflight passed; receipt: $status_file"
