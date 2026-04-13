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

This builds the debug APK in local-only mode, installs it on the emulator, and runs all flows.

## Running Individual Flows

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

## Directory Structure

```
e2e/
├── flows/
│   └── android/
│       ├── P0_app_launch.yaml
│       ├── P0_create_project.yaml
│       ├── P0_row_counter.yaml
│       ├── P1_edit_project.yaml
│       ├── P1_search_filter.yaml
│       └── P2_navigation.yaml
├── run-android.sh
└── README.md
```

## Test Isolation

Each flow starts with `clearState: com.knitnote.android` which clears the entire app sandbox (database, shared preferences, files). This ensures each flow runs from a clean state.

The app runs in **local-only mode** (no Supabase credentials) so no network/auth is required. The build script overrides `local.properties` credentials with empty env vars.

## Selector Strategy

| Context | Selector | Reason |
|---------|----------|--------|
| Main content | `testTag` via `id:` | Stable, not affected by text changes |
| Dialog content | Text-based (`tapOn: "Label"`) | CMP `AlertDialog` renders in a separate window where `testTagsAsResourceId` doesn't propagate |
| Navigation | `contentDescription` via `text:` | Icons have no visible text |

`testTagsAsResourceId = true` is set on the root `Box` in `MainActivity` to expose `testTag` values as `resource-id` for Maestro.

## Flows

### P0 (Critical)

| Flow | Description |
|------|-------------|
| `P0_app_launch` | App launches, empty state visible, FAB visible |
| `P0_create_project` | Create a project via dialog, verify it appears in list |
| `P0_row_counter` | Create project, navigate to detail, increment/decrement counter |

### P1 (Important)

| Flow | Description |
|------|-------------|
| `P1_edit_project` | Edit project title and total rows from detail screen |
| `P1_search_filter` | Search by name, filter by status (All/In Progress/Not Started) |

### P2 (Nice to Have)

| Flow | Description |
|------|-------------|
| `P2_navigation` | Navigate between ProjectList, Profile, Settings, and ProjectDetail |

## Known Limitations

- **Swipe-to-delete**: Maestro's `swipe` gesture does not trigger Compose Multiplatform's `SwipeToDismissBox`. The low-level `adb shell input swipe` works, but cannot be used within Maestro YAML flows. Swipe-to-delete is tested via Android instrumentation tests instead.
- **Dialog testTags**: `Modifier.testTag()` inside `AlertDialog` is not accessible to Maestro because the dialog renders in a separate window. Text-based selectors are used as a workaround.

## Future Phases

- **Phase 20c**: iOS Maestro flows (mirror Android flows on iOS simulator)
- **Phase 20d**: CI integration (`e2e.yml` workflow, `main` push + tags only)
