#!/usr/bin/env bash
set -euo pipefail

# Run after Central publication from the exact release tag checkout.
: "${GITHUB_REF_NAME:?release tag is required}"
: "${GITHUB_REPOSITORY:?release repository is required}"

prerelease=false
notes_file=docs/release-notes/0.1.0.md
release_args=(
  "$GITHUB_REF_NAME"
  --verify-tag
  --title "frame4s ${GITHUB_REF_NAME#v}"
)
if [[ "$GITHUB_REF_NAME" =~ -RC[0-9]+$ ]]; then
  prerelease=true
  release_args+=(--prerelease)
else
  : "${RUNNER_TEMP:?runner temporary directory is required}"
  notes_file="$RUNNER_TEMP/release-notes-stable.md"
  sed 's/^Status: release-candidate draft$/Status: stable/' \
    docs/release-notes/0.1.0.md > "$notes_file"
fi
release_args+=(--notes-file "$notes_file")

# A failed read must stop the job, not be mistaken for an absent release.
# Pagination also covers retries of older tags after newer releases exist.
existing_release="$(
  gh api "repos/$GITHUB_REPOSITORY/releases" --paginate \
    --jq '.[] | select(.tag_name == env.GITHUB_REF_NAME) | [.draft, .prerelease] | @tsv'
)"
if [[ -n "$existing_release" ]]; then
  if [[ "$existing_release" != $'false\t'"$prerelease" ]]; then
    echo "Existing release $GITHUB_REF_NAME has an unexpected draft/prerelease status." >&2
    exit 1
  fi
  echo "GitHub release $GITHUB_REF_NAME already exists with the expected status."
  exit 0
fi

gh release create "${release_args[@]}"
