# Knit Note E2E Tests

End-to-end tests using [Maestro](https://maestro.mobile.dev/) for the Knit Note app.

## Prerequisites

1. **Maestro CLI**: Install via `curl -Ls "https://get.maestro.mobile.dev" | bash` or `brew install maestro`
2. **Android**: Running emulator or connected device (`adb devices` should list a device)
3. **Java 17+**: Required for Gradle build

## Quick Start (Android)

```bash
# From project root
./e2e/run-android.sh
```

This builds the debug APK, installs it on the emulator, and runs all P0 flows.

## Running Individual Flows

```bash
maestro test e2e/flows/android/P0_app_launch.yaml
maestro test e2e/flows/android/P0_create_project.yaml
maestro test e2e/flows/android/P0_row_counter.yaml
```

## Directory Structure

```
e2e/
├── flows/
│   └── android/           # Android-specific flows
│       ├── P0_app_launch.yaml
│       ├── P0_create_project.yaml
│       └── P0_row_counter.yaml
├── run-android.sh          # Build + install + run (Android)
└── README.md
```

## Test Isolation

Each flow starts with `clearState: com.knitnote.android` which clears the entire app sandbox (database, shared preferences, files). This ensures each flow runs from a clean state.

The app runs in **local-only mode** (no Supabase credentials) so no network/auth is required.

## Design Decisions

- **Maestro `clearState`** is used instead of an in-app database reset mechanism. This is simpler and more thorough than selective file deletion.
- **`testTag` identifiers** are added to key Compose UI elements for stable selectors. Names match iOS `accessibilityIdentifier` values where applicable.
- **Text-based selectors** are used as fallback for less critical elements (dialog titles, status text).

## P0 Flows

| Flow | Description |
|------|-------------|
| `P0_app_launch` | App launches, empty state visible, FAB visible |
| `P0_create_project` | Create a project via dialog, verify it appears in list |
| `P0_row_counter` | Create project, navigate to detail, increment/decrement counter |

## Future Phases

- **Phase 20b**: P1+P2 Android flows (edit, delete, search/filter, navigation)
- **Phase 20c**: iOS Maestro flows
- **Phase 20d**: CI integration (`e2e.yml` workflow, `main` push + tags only)
