# Knit Note E2E Tests

End-to-end tests using [Maestro](https://maestro.mobile.dev/) for the Knit Note app.

## Prerequisites

1. **Maestro CLI**: Install via `curl -Ls "https://get.maestro.mobile.dev" | bash` or `brew install maestro`
2. **Android**: Running emulator or connected device (`adb devices` should list a device)
3. **iOS**: Booted simulator (`xcrun simctl list devices booted` should list a device)
4. **Java 17+**: Required for Gradle build
5. **Xcode 16+**: Required for iOS build

## Quick Start

### Android

```bash
# From project root
./e2e/run-android.sh
```

This builds the debug APK in local-only mode, installs it on the emulator, and runs all flows.

### iOS

```bash
# From project root (requires booted simulator)
./e2e/run-ios.sh
```

This builds the iOS app for the simulator in local-only mode (via `xcodebuild`), installs it, and runs all flows.

## Running Individual Flows

### Android

```bash
# P0 (critical)
maestro test e2e/flows/android/P0_app_launch.yaml
maestro test e2e/flows/android/P0_create_project.yaml
maestro test e2e/flows/android/P0_row_counter.yaml

# P1 (important)
maestro test e2e/flows/android/P1_edit_project.yaml
maestro test e2e/flows/android/P1_search_filter.yaml

# P2 (nice to have)
maestro test e2e/flows/android/P2_navigation.yaml
```

### iOS

```bash
# P0 (critical)
maestro test e2e/flows/ios/P0_app_launch.yaml
maestro test e2e/flows/ios/P0_create_project.yaml
maestro test e2e/flows/ios/P0_row_counter.yaml

# P1 (important)
maestro test e2e/flows/ios/P1_edit_project.yaml
maestro test e2e/flows/ios/P1_search_filter.yaml

# P2 (nice to have)
maestro test e2e/flows/ios/P2_navigation.yaml
```

## Directory Structure

```
e2e/
├── flows/
│   ├── android/
│   │   ├── P0_app_launch.yaml
│   │   ├── P0_create_project.yaml
│   │   ├── P0_row_counter.yaml
│   │   ├── P1_edit_project.yaml
│   │   ├── P1_search_filter.yaml
│   │   └── P2_navigation.yaml
│   └── ios/
│       ├── P0_app_launch.yaml
│       ├── P0_create_project.yaml
│       ├── P0_row_counter.yaml
│       ├── P1_edit_project.yaml
│       ├── P1_search_filter.yaml
│       └── P2_navigation.yaml
├── run-android.sh
├── run-ios.sh
└── README.md
```

## Test Isolation

Each flow starts with `clearState: <bundleId>` which clears the entire app sandbox (database, shared preferences/UserDefaults, files). This ensures each flow runs from a clean state.

Both platforms run in **local-only mode** (no Supabase credentials) so no network/auth is required:
- **Android**: The build script overrides `local.properties` credentials with empty env vars
- **iOS**: The build script exports empty `SUPABASE_URL`/`SUPABASE_ANON_KEY` env vars that propagate to the Gradle pre-build script

## Selector Strategy

### Android

| Context | Selector | Reason |
|---------|----------|--------|
| Main content | `testTag` via `id:` | Stable, not affected by text changes |
| Dialog content | Text-based (`tapOn: "Label"`) | CMP `AlertDialog` renders in a separate window where `testTagsAsResourceId` doesn't propagate |
| Navigation | `contentDescription` via `text:` | Icons have no visible text |

`testTagsAsResourceId = true` is set on the root `Box` in `MainActivity` to expose `testTag` values as `resource-id` for Maestro.

### iOS

| Context | Selector | Reason |
|---------|----------|--------|
| Main content | `accessibilityIdentifier` via `id:` | Stable, not affected by text changes |
| Sheet content | Text-based (`tapOn: "Label"`) | SwiftUI Form TextFields have visible placeholder/label text |
| Navigation | `accessibilityLabel` via text matching | Back button shows previous screen title; icons use accessibilityLabel |
| Menu items | Text-based (`tapOn: "Item"`) | SwiftUI Menu renders items with text labels in system popover |

## Platform-Specific Differences

| Aspect | Android | iOS |
|--------|---------|-----|
| App ID | `io.github.b150005.knitnote` | `io.github.b150005.knitnote` |
| Empty state title | "No projects yet" | "No Projects Yet" |
| Create form field | "Project Name" | "Project Title" |
| Back button | `contentDescription: "Back"` | Previous screen's `navigationTitle` (e.g., "Knit Note") |
| Menu navigation | Direct text tap (items visible) | Tap `moreMenu` first, then text tap (Menu dropdown) |
| Search clear | `contentDescription: "Clear search"` | "Cancel" button (iOS search bar) |

## Flows

### P0 (Critical)

| Flow | Description |
|------|-------------|
| `P0_app_launch` | App launches, empty state visible, create button visible |
| `P0_create_project` | Create a project via dialog/sheet, verify it appears in list |
| `P0_row_counter` | Create project, navigate to detail, increment/decrement counter |

### P1 (Important)

| Flow | Description |
|------|-------------|
| `P1_edit_project` | Edit project title and total rows from detail screen |
| `P1_search_filter` | Search by name, filter by status (**iOS: skipped**, tagged `skip-ios26`) |

### P2 (Nice to Have)

| Flow | Description |
|------|-------------|
| `P2_navigation` | Navigate between ProjectList, Profile, Settings, and ProjectDetail |

## Known Limitations

### Both Platforms
- **Swipe-to-delete**: Maestro's `swipe` gesture does not trigger Compose Multiplatform's `SwipeToDismissBox` (Android) or SwiftUI's `swipeActions` reliably. Swipe-to-delete is tested via platform instrumentation tests instead.

### Android
- **Dialog testTags**: `Modifier.testTag()` inside `AlertDialog` is not accessible to Maestro because the dialog renders in a separate window. Text-based selectors are used as a workaround.

### iOS (Maestro 2.4.0 + iOS 26)
- **SwiftUI List Button tap bug**: Maestro 2.4.0 on iOS 26 can only trigger a SwiftUI `Button` action ONCE per screen when the button is inside a `List` section. Subsequent taps on any button in the same `List` are silently dropped (Maestro reports `COMPLETED` but the action handler does not fire). This affects:
  - **P0_row_counter**: Limited to a single increment (multi-tap tested via XCTest)
  - **P1_search_filter**: Skipped entirely (tagged `skip-ios26`); filter chips inside `List` sections are unreachable
- **`.searchable` text binding**: Maestro's `inputText` types text into the iOS search bar but does not update the SwiftUI `@State` binding, so `.searchable` filtering does not activate.
- **Menu timing**: iOS Menu popover animation may require a brief wait before tapping items. Maestro's default retry handles this in most cases.

## CI Integration

E2E tests run automatically via the `e2e.yml` GitHub Actions workflow on:
- Push to `main`
- Push of `v*` tags

### Android CI

The Android job uses `reactivecircus/android-emulator-runner` to spin up an API 34 x86_64 emulator with KVM acceleration. The APK is built in local-only mode (no Supabase) and installed on the emulator before Maestro runs all flows.

### iOS CI

The iOS job runs on `macos-latest`, builds the app for the simulator, boots an available iPhone simulator, installs the app, and runs Maestro flows with `--exclude-tags skip-ios26`.

### Artifacts

On failure, Maestro screenshots are uploaded as GitHub Actions artifacts (`maestro-android-screenshots` / `maestro-ios-screenshots`) with 7-day retention.

### Running locally

Use the local scripts for development:

```bash
# Android (requires running emulator)
./e2e/run-android.sh

# iOS (requires booted simulator)
./e2e/run-ios.sh
```
