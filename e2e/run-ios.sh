#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DERIVED_DATA="$PROJECT_ROOT/build/ios-e2e"

echo "=== Knit Note E2E: iOS Flows ==="

# Step 1: Build iOS app for simulator in local-only mode.
# Empty Supabase credentials cause the app to skip auth (same as Android).
# The Xcode pre-build script invokes Gradle to compile the shared framework,
# so the env vars propagate to the KMP build as well.
echo "[1/5] Building iOS app for simulator (local-only mode)..."
cd "$PROJECT_ROOT"
export SUPABASE_URL=""
export SUPABASE_ANON_KEY=""
BUILD_LOG="$DERIVED_DATA/build.log"
mkdir -p "$DERIVED_DATA"
xcodebuild \
  -project iosApp/iosApp.xcodeproj \
  -scheme iosApp \
  -sdk iphonesimulator \
  -configuration Debug \
  -destination 'generic/platform=iOS Simulator' \
  -derivedDataPath "$DERIVED_DATA" \
  build > "$BUILD_LOG" 2>&1 || {
  echo "ERROR: xcodebuild failed. Last 50 lines:"
  tail -50 "$BUILD_LOG"
  exit 1
}
echo "  BUILD SUCCEEDED"

# Step 2: Locate the built .app
echo "[2/5] Locating built app..."
APP_PATH=$(find "$DERIVED_DATA" -name "iosApp.app" -path "*/Debug-iphonesimulator/*" -type d | head -1)
if [ -z "$APP_PATH" ]; then
  echo "ERROR: Built app not found under $DERIVED_DATA"
  exit 1
fi
echo "  App: $APP_PATH"

# Step 3: Verify a simulator is booted
echo "[3/5] Checking for booted simulator..."
command -v python3 >/dev/null 2>&1 || { echo "ERROR: python3 is required for simulator UDID lookup"; exit 1; }
BOOTED_UDID=$(xcrun simctl list devices booted -j | python3 -c "
import json, sys
data = json.load(sys.stdin)
for runtime, devices in data.get('devices', {}).items():
    for d in devices:
        if d.get('state') == 'Booted':
            print(d['udid'])
            sys.exit(0)
sys.exit(1)
" 2>/dev/null) || true

if [ -z "$BOOTED_UDID" ]; then
  echo "ERROR: No booted simulator found. Start one via Xcode or:"
  echo "  xcrun simctl boot 'iPhone 16'"
  exit 1
fi
echo "  Simulator: $BOOTED_UDID"

# Step 4: Install app on the booted simulator
echo "[4/5] Installing app on simulator..."
xcrun simctl install "$BOOTED_UDID" "$APP_PATH"

# Step 5: Run Maestro flows
# --exclude-tags skip-ios26: Skip flows affected by Maestro 2.x + iOS 26
# SwiftUI Button tap bug (search/filter). Re-enable when Maestro ships a fix.
# --exclude-tags requires-supabase: Skip flows that need a live backend.
echo "[5/5] Running Maestro flows (P0 + P1 + P2, excluding skip-ios26 + requires-supabase)..."
maestro --device "$BOOTED_UDID" test --exclude-tags skip-ios26,requires-supabase "$SCRIPT_DIR/flows/ios/"

echo "All flows passed!"
