#!/usr/bin/env bash
# Verify that the three string resource files share the same key set.
#
# Sources:
#   - androidApp/src/main/res/values/strings.xml        (English, Android)
#   - androidApp/src/main/res/values-ja/strings.xml     (Japanese, Android)
#   - iosApp/iosApp/Resources/Localizable.xcstrings     (both locales, iOS)
#
# Exits 1 on any drift so CI can block.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EN_XML="$ROOT/androidApp/src/main/res/values/strings.xml"
JA_XML="$ROOT/androidApp/src/main/res/values-ja/strings.xml"
IOS_CATALOG="$ROOT/iosApp/iosApp/Resources/Localizable.xcstrings"

for f in "$EN_XML" "$JA_XML" "$IOS_CATALOG"; do
  if [[ ! -f "$f" ]]; then
    echo "ERROR: missing file: $f" >&2
    exit 1
  fi
done

# Extract keys sorted and deduplicated
extract_android_keys() {
  grep -oE 'name="[^"]+"' "$1" | sed -E 's/name="([^"]+)"/\1/' | sort -u
}

extract_ios_keys() {
  # Top-level entries under the "strings" object are the keys.
  # Prefer jq; fall back to a grep that accepts 2-space or 4-space
  # indent because Xcode serializes .xcstrings at 2-space on re-save.
  if command -v jq >/dev/null 2>&1; then
    jq -r '.strings | keys[]' "$1" | sort -u
  else
    grep -E '^( {2}| {4})"[^"]+" : \{$' "$1" \
      | sed -E 's/^ +"([^"]+)" : \{$/\1/' \
      | sort -u
  fi
}

en_keys="$(extract_android_keys "$EN_XML")"
ja_keys="$(extract_android_keys "$JA_XML")"
ios_keys="$(extract_ios_keys "$IOS_CATALOG")"

# Guard against silent extraction failures: empty output would make `comm`
# treat a blank line as a key and report false drift.
for pair in "values/strings.xml:$en_keys" "values-ja/strings.xml:$ja_keys" "Localizable.xcstrings:$ios_keys"; do
  label="${pair%%:*}"
  value="${pair#*:}"
  if [[ -z "$value" ]]; then
    echo "ERROR: extracted zero keys from $label — check the source file format." >&2
    exit 1
  fi
done

fail=0

diff_keys() {
  local label="$1" a="$2" b="$3"
  local only_a only_b
  only_a="$(comm -23 <(printf '%s\n' "$a") <(printf '%s\n' "$b"))"
  only_b="$(comm -13 <(printf '%s\n' "$a") <(printf '%s\n' "$b"))"
  if [[ -n "$only_a" || -n "$only_b" ]]; then
    fail=1
    echo "DRIFT [$label]" >&2
    if [[ -n "$only_a" ]]; then
      echo "  keys only on left:" >&2
      echo "$only_a" | sed 's/^/    /' >&2
    fi
    if [[ -n "$only_b" ]]; then
      echo "  keys only on right:" >&2
      echo "$only_b" | sed 's/^/    /' >&2
    fi
  fi
}

diff_keys "values vs values-ja" "$en_keys" "$ja_keys"
diff_keys "values vs iOS xcstrings" "$en_keys" "$ios_keys"

if [[ $fail -ne 0 ]]; then
  echo "" >&2
  echo "i18n key drift detected. Add/remove the missing keys in all three files." >&2
  exit 1
fi

count=$(echo "$en_keys" | wc -l | tr -d ' ')
echo "OK: $count keys synchronized across Android (en/ja) and iOS String Catalog."
