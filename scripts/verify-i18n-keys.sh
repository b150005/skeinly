#!/usr/bin/env bash
# Verify that all string resource files share the same key set.
#
# Sources:
#   - androidApp/src/main/res/values/strings.xml                         (English, Android native)
#   - androidApp/src/main/res/values-ja/strings.xml                      (Japanese, Android native)
#   - shared/src/commonMain/composeResources/values/strings.xml          (English, shared Compose)
#   - shared/src/commonMain/composeResources/values-ja/strings.xml       (Japanese, shared Compose)
#   - iosApp/iosApp/Resources/Localizable.xcstrings                      (both locales, iOS SwiftUI)
#
# Why two Android-side sources? `androidApp/.../strings.xml` resolves via
# `R.string.*` for Android-native code (Activity/manifest + any direct
# `R`-reference). `shared/.../composeResources/...` resolves via
# `Res.string.*` from `org.jetbrains.compose.resources.stringResource` for
# shared Compose Multiplatform screens (which cannot see `R`). Keys MUST
# stay identical across both Android-side files so any change applies
# everywhere. See docs/en/i18n-convention.md.
#
# Exits 1 on any drift so CI can block.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
EN_XML="$ROOT/androidApp/src/main/res/values/strings.xml"
JA_XML="$ROOT/androidApp/src/main/res/values-ja/strings.xml"
SHARED_EN_XML="$ROOT/shared/src/commonMain/composeResources/values/strings.xml"
SHARED_JA_XML="$ROOT/shared/src/commonMain/composeResources/values-ja/strings.xml"
IOS_CATALOG="$ROOT/iosApp/iosApp/Resources/Localizable.xcstrings"

for f in "$EN_XML" "$JA_XML" "$SHARED_EN_XML" "$SHARED_JA_XML" "$IOS_CATALOG"; do
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
shared_en_keys="$(extract_android_keys "$SHARED_EN_XML")"
shared_ja_keys="$(extract_android_keys "$SHARED_JA_XML")"
ios_keys="$(extract_ios_keys "$IOS_CATALOG")"

# Guard against silent extraction failures: empty output would make `comm`
# treat a blank line as a key and report false drift.
for pair in \
  "androidApp values/strings.xml:$en_keys" \
  "androidApp values-ja/strings.xml:$ja_keys" \
  "shared composeResources/values/strings.xml:$shared_en_keys" \
  "shared composeResources/values-ja/strings.xml:$shared_ja_keys" \
  "iosApp Localizable.xcstrings:$ios_keys"; do
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

# All comparisons pivot on `en_keys` (androidApp values/strings.xml) as the
# canonical source. Any drift on any other source surfaces here.
diff_keys "androidApp values (en) vs values-ja (ja)" "$en_keys" "$ja_keys"
diff_keys "androidApp values (en) vs shared composeResources values (en)" "$en_keys" "$shared_en_keys"
diff_keys "shared composeResources values (en) vs values-ja (ja)" "$shared_en_keys" "$shared_ja_keys"
diff_keys "androidApp values (en) vs iOS xcstrings" "$en_keys" "$ios_keys"

if [[ $fail -ne 0 ]]; then
  echo "" >&2
  echo "i18n key drift detected. Add/remove the missing keys in all five files." >&2
  exit 1
fi

count=$(echo "$en_keys" | wc -l | tr -d ' ')
echo "OK: $count keys synchronized across androidApp (en/ja), shared composeResources (en/ja), and iOS String Catalog."
