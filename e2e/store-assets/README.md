# Store Screenshot Capture

Maestro flows for capturing App Store / Play Store screenshots.

## Prerequisites

- Maestro CLI installed (`brew install maestro`)
- App built and installed on emulator/simulator
- For Android: emulator running with local-only build
- For iOS: simulator running with local-only build

## Running

### Android
```bash
# Build and install the app first
cd /path/to/knit-note
./gradlew :androidApp:installDebug

# Capture screenshots
maestro test e2e/store-assets/capture_android.yaml
```

### iOS
```bash
# Build via Xcode or xcodebuild first
# Then capture screenshots
maestro test e2e/store-assets/capture_ios.yaml
```

## Output

Screenshots are saved to `~/.maestro/screenshots/` by default.

Files are named:
- `screenshot_01_onboarding.png`
- `screenshot_02_empty_state.png`
- `screenshot_03_project_list.png`
- `screenshot_04_row_counter.png`
- `screenshot_05_search_filter.png` (Android only)
- `screenshot_06_pattern_library.png`
- `screenshot_07_profile.png`

## Post-Processing

After capturing raw screenshots:
1. Frame them with device bezels (using tools like `fastlane frameit` or Figma)
2. Add caption text overlays (see `docs/en/store-listing.md` for captions)
3. Export at required sizes for each store

## Required Sizes

| Store | Size | Notes |
|-------|------|-------|
| App Store (6.7") | 1290 x 2796 | iPhone 15 Pro Max |
| App Store (5.5") | 1242 x 2208 | iPhone 8 Plus (if supporting) |
| Play Store | 1080+ width | Any modern phone resolution |
