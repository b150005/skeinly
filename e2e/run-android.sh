#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
APK_PATH="$PROJECT_ROOT/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
APP_ID="com.knitnote.android"

echo "=== Knit Note E2E: Android P0 Flows ==="

# Step 1: Build debug APK (local-only mode — no Supabase env vars)
echo "[1/4] Building debug APK..."
cd "$PROJECT_ROOT"
./gradlew :androidApp:assembleDebug -q

# Step 2: Verify APK exists
if [ ! -f "$APK_PATH" ]; then
  echo "ERROR: APK not found at $APK_PATH"
  exit 1
fi

# Step 3: Verify emulator/device is connected
echo "[2/4] Checking for connected device..."
DEVICE_COUNT=$(adb devices | tail -n +2 | grep -c "device$" || true)
if [ "$DEVICE_COUNT" -eq 0 ]; then
  echo "ERROR: No running emulator or connected device found. Start one first."
  exit 1
fi

# Step 4: Install APK on emulator/device
echo "[3/4] Installing APK..."
adb install -r "$APK_PATH"

# Step 5: Run Maestro flows
echo "[4/4] Running Maestro P0 flows..."
maestro test "$SCRIPT_DIR/flows/android/"

echo "All P0 flows passed!"
