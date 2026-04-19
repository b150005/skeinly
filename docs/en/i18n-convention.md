# i18n Convention (Phase 33)

Last updated: 2026-04-19.

## Goals

1. Every user-visible string on Android and iOS resolves through a
   localization resource, not a hardcoded literal.
2. Both platforms share the **same key set**. A CI check fails the build
   if the sets drift.
3. Japanese and English are both first-class at launch. Additional
   locales are added by translating values only; keys do not change.

## Source of truth

| Locale | Android | iOS |
|---|---|---|
| English (default / development region) | `androidApp/src/main/res/values/strings.xml` | `iosApp/iosApp/Resources/Localizable.xcstrings` (`"en"` localization) |
| Japanese | `androidApp/src/main/res/values-ja/strings.xml` | `iosApp/iosApp/Resources/Localizable.xcstrings` (`"ja"` localization) |

The iOS side uses Xcode 15+ **String Catalog** (`.xcstrings`, JSON). It
holds both locales in a single file. `project.yml` declares
`CFBundleDevelopmentRegion: en` and `CFBundleLocalizations: [en, ja]`.

## Key naming

- `snake_case`.
- Prefix by role, not by screen:
  - `action_*` for buttons, menu items, accessibility labels that name an
    action (`action_save`, `action_undo`).
  - `state_*` for load / empty / idle state labels (`state_loading`).
  - `error_*` for error-message text (`error_load`, `error_save`).
  - `dialog_*_title` / `dialog_*_body` for confirmation dialogs.
- Only add a screen prefix when the same word legitimately means two
  different things in two places (rare). Prefer role-prefixed reusable
  keys.

## How to reference

**Android (Jetpack Compose)**:

```kotlin
import androidx.compose.ui.res.stringResource
import io.github.b150005.knitnote.android.R

Text(text = stringResource(R.string.action_save))
```

**iOS (SwiftUI)**:

```swift
Text("action_save")   // Text(_:tableName:) auto-localizes
Button("action_save") { ... }
```

When you need a `String` (not a `Text`), use `String(localized:)`:

```swift
let label = String(localized: "action_save")
```

SwiftUI automatically resolves keys against `Localizable.xcstrings` when
the literal matches a key. Do not wrap with `NSLocalizedString`.

## Adding a key

1. Add the entry to **all three** sources:
   - `androidApp/src/main/res/values/strings.xml`
   - `androidApp/src/main/res/values-ja/strings.xml`
   - `iosApp/iosApp/Resources/Localizable.xcstrings`
2. Run locally:
   ```
   ./scripts/verify-i18n-keys.sh
   ```
3. Key sync is enforced in CI — missing a platform will fail the build.

## Removing a key

Remove from all three sources in the same commit. The verifier will
catch partial removals.

## Non-goals in Phase 33

- Migration of existing screens. Existing hardcoded strings stay
  hardcoded until a focused i18n sweep is scheduled. Net-new UI (Phase
  32 editor onward) writes localized from day one.
- Symbol Catalog bilingual labels. `SymbolCatalog` currently exposes
  both JA and EN labels on every glyph (per the Phase 30.1 gallery
  design). A later phase will pick locale-appropriate labels via
  `Locale.current`; for now both remain visible.
- Pluralization. Add `@plural` / `stringsdict` when the first plural
  key is introduced.

## Related

- Script: [`scripts/verify-i18n-keys.sh`](../../scripts/verify-i18n-keys.sh)
- CI step: `.github/workflows/ci.yml` → `Verify i18n key sync`
- ADR-008 (symbol catalog) references this convention for future locale
  switching of symbol labels.
