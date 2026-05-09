#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
APK_PATH="$PROJECT_ROOT/androidApp/build/outputs/apk/debug/androidApp-debug.apk"

# Phase 24.2 (ADR-017 §3.5) — Android dev/prod separation. Debug builds
# carry `applicationIdSuffix = ".dev"` (set in androidApp/build.gradle.kts
# debug buildType), so the runtime package name is the suffixed form.
# Maestro flows under flows/android/ + helpers/*_android.yaml reference
# the appId via `${APP_ID}` — exported here so all flow runs target the
# debug variant uniformly. The release variant keeps the unsuffixed
# `io.github.b150005.skeinly` bundle id and is published only via
# `gradle-play-publisher` (see androidApp/build.gradle.kts `play { … }`).
export APP_ID="io.github.b150005.skeinly.dev"

# Defensive cleanup on script exit (success, failure, or interrupt). Kills
# any `maestro test` process this user owns — covers both the run we just
# launched and any straggler from an interrupted prior invocation. Scope is
# limited to the current $USER; other users on a shared machine are
# untouched. `maestro mcp` daemons from Claude Code MCP integration are
# explicitly NOT killed here — that's the `make e2e-clean` 4h-threshold
# rule's job (so an active MCP integration in another window is preserved).
trap 'pkill -u "$USER" -9 -f "maestro\\.cli\\.AppKt test" >/dev/null 2>&1 || true' EXIT INT TERM

echo "=== Skeinly E2E: Android Flows ==="

# Step 1: Build debug APK in local-only mode (empty Supabase credentials
# override local.properties so the app skips the auth screen).
echo "[1/5] Building debug APK (local-only mode)..."
cd "$PROJECT_ROOT"
SUPABASE_URL="" SUPABASE_ANON_KEY="" ./gradlew :androidApp:assembleDebug -q

# Step 2: Verify APK exists
echo "[2/5] Verifying APK..."
if [ ! -f "$APK_PATH" ]; then
  echo "ERROR: APK not found at $APK_PATH"
  exit 1
fi

# Step 3: Verify emulator/device is connected
echo "[3/5] Checking for connected device..."
DEVICE_COUNT=$(adb devices | tail -n +2 | grep -c "device$" || true)
if [ "$DEVICE_COUNT" -eq 0 ]; then
  echo "ERROR: No running emulator or connected device found. Start one first."
  exit 1
fi

# Step 4: Install APK on emulator/device
echo "[4/5] Installing APK..."
adb install -r "$APK_PATH"

# Step 5: Run Maestro flows
# Note: --exclude-tags requires-supabase skips flows that need a live backend.
# Remove the flag when running against a Supabase-connected build.
echo "[5/5] Running Maestro flows (P0 + P1 + P2)..."
maestro test --exclude-tags requires-supabase "$SCRIPT_DIR/flows/android/"

echo "All flows passed!"
