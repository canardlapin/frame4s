#!/usr/bin/env bash
set -euo pipefail
export LC_ALL=C
export LANG=C

if [[ $# -ne 2 ]]; then
  echo "usage: $0 <source-files.sha256> <source-files.list>" >&2
  exit 2
fi

manifest="$1"
expected="$2"
script_directory="$(cd "$(dirname "$0")" && pwd)"
repository="$(cd "$script_directory/.." && pwd)"
temporary="$(mktemp -d "${TMPDIR:-/tmp}/frame4s-source-manifest.XXXXXX")"
trap 'rm -rf "$temporary"' EXIT

if [[ ! -f "$manifest" ]]; then
  echo "manifest missing: $manifest" >&2
  exit 1
fi
if [[ ! -f "$expected" ]]; then
  echo "expected-path list missing: $expected" >&2
  exit 1
fi

manifest_paths="$temporary/manifest-paths"
expected_paths="$temporary/expected-paths"

awk '
  NF != 2 || length($1) != 64 || $1 !~ /^[[:xdigit:]]+$/ {
    printf "invalid manifest line %d: %s\n", NR, $0 > "/dev/stderr"
    invalid = 1
    next
  }
  { print $2 }
  END { if (invalid) exit 1 }
' "$manifest" >"$manifest_paths"

awk '
  NF != 1 {
    printf "invalid expected-path line %d: %s\n", NR, $0 > "/dev/stderr"
    invalid = 1
    next
  }
  { print $1 }
  END { if (invalid) exit 1 }
' "$expected" >"$expected_paths"

duplicate_manifest="$(sort "$manifest_paths" | uniq -d)"
if [[ -n "$duplicate_manifest" ]]; then
  echo "duplicate manifest path: $duplicate_manifest" >&2
  exit 1
fi
duplicate_expected="$(sort "$expected_paths" | uniq -d)"
if [[ -n "$duplicate_expected" ]]; then
  echo "duplicate expected path: $duplicate_expected" >&2
  exit 1
fi

sort "$manifest_paths" >"$temporary/manifest-sorted"
sort "$expected_paths" >"$temporary/expected-sorted"

missing="$(comm -23 "$temporary/expected-sorted" "$temporary/manifest-sorted")"
if [[ -n "$missing" ]]; then
  echo "manifest path missing: $missing" >&2
  exit 1
fi
extra="$(comm -13 "$temporary/expected-sorted" "$temporary/manifest-sorted")"
if [[ -n "$extra" ]]; then
  echo "unexpected manifest path: $extra" >&2
  exit 1
fi

while IFS= read -r path || [[ -n "$path" ]]; do
  case "$path" in
    /* | ../* | */../* | */..)
      echo "manifest path escapes repository: $path" >&2
      exit 1
      ;;
  esac
  target="$repository/$path"
  if [[ ! -f "$target" ]]; then
    echo "manifest target missing: $path" >&2
    exit 1
  fi
  recorded="$(awk -v target="$path" '$2 == target { print $1 }' "$manifest")"
  actual="$(shasum -a 256 "$target" | awk '{ print $1 }')"
  if [[ "$recorded" != "$actual" ]]; then
    echo "manifest hash mismatch: $path" >&2
    echo "recorded=$recorded" >&2
    echo "actual=$actual" >&2
    exit 1
  fi
done <"$expected_paths"

echo "source manifest verified: $(wc -l <"$expected_paths" | tr -d ' ') paths"
