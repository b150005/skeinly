# Y3-iOS — iOS Swift catalog locale-aware label resolver consolidation

## Status

`READY_FOR_CONSOLIDATION`  <!-- PLANNED | IN_PROGRESS | BLOCKED | READY_FOR_CONSOLIDATION | CLOSED -->

Pushed SHA: `fdbc2eb` (refactor code + Bridging extension on origin/main)

## Scope

Consolidate the residual 4 inline `(Locale.current.language.languageCode?.identifier == "ja") ? def.jaLabel : def.enLabel` ternaries on the iOS Swift side into a single bridging-layer helper, mirroring the Y3 Kotlin half (`SymbolDefinition.localizedLabel(locale: String)` extension at `shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/symbol/LocalizedLabel.kt`, commit `d09d68a`). Behavior-equivalent visual-only refactor of the 4 sites + 1 new Swift extension file in `iosApp/iosApp/Core/Bridging/` matching the `UgcReportCategory+Localized.swift` / `ErrorMessage+Localized.swift` convention.

**OUT of scope**: any change to the 3 shared i18n files, the Y3 Kotlin extension contract, `SymbolDefinition` / `SymbolCategory` data classes, `ChartAccessibility.kt`, XCUITest additions (Y3 Kotlin precedent: visual refactor, contract test lives in `commonTest` already — no Swift-side test mirror needed because the 4 call sites are not testable in isolation without the broader VoiceOver harness).

## Declared write-set

- `iosApp/iosApp/Core/Bridging/SymbolDefinition+Localized.swift`  <!-- new file -->
- `iosApp/iosApp/Screens/ChartEditorScreen.swift`
- `iosApp/iosApp/Screens/ChartComparisonAccessibility.swift`
- `iosApp/iosApp/Screens/ChartEditorAccessibility.swift`
- `iosApp/iosApp/Screens/ChartViewerAccessibility.swift`
- `local.properties`  <!-- worktree bootstrap only, not committed (in .gitignore) -->
- `docs/en/phase/tasks/Y3-iOS.md`  <!-- this file -->

No i18n keys added; no `.tsv` fragment shipped. No ADR/spec edits — Y3 ADR-016 / accessibility audit refs already cover the resolver pattern; this task closes the implementation-half tech-debt only.

XcodeGen `project.yml` edit NOT required — the iosApp target `sources:` glob is `iosApp` with only `Info.plist` excluded, so new `Core/Bridging/*.swift` files are picked up automatically (verified `iosApp/project.yml:101-104`).

## ADR / Spec refs

- Y3 Kotlin precedent: commit `d09d68a` ("refactor(a11y): Y3 — consolidate locale-aware symbol label resolver"), `shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/symbol/LocalizedLabel.kt`
- Y3 contract test: `shared/src/commonTest/kotlin/io/github/b150005/skeinly/domain/symbol/SymbolDefinitionLocalizedLabelTest.kt` (14 assertions; `ja` / `ja_JP` / `JA` / `en` / `en_US` / `fr` / `""` × both `SymbolDefinition` and `SymbolCategory`)
- Tech-debt entry being closed: `docs/en/phase/tech-debt.md` L69 "Y3-iOS — iOS Swift catalog locale-aware resolver consolidation" — orchestrator folds at consolidation
- X3 surface origin (R1b Follow-up #1): inline comments at all 4 call sites reference "X3 follow-up Catalog locale-aware symbol label resolver"
- No ADR change — Y3 did not amend any ADR; this is a pure refactor

## Test delta

- Planned: **+0 XCUITest** / **+0 commonTest** / **+0 Deno**. Visual-only refactor; Y3's 14 `commonTest` assertions cover the predicate contract via the Kotlin extension. Swift extension is a 1-line `locale.lowercased().hasPrefix("ja") ? jaLabel : enLabel` mirroring the same predicate — no behavior surface that XCUITest could meaningfully exercise without exercising the broader VoiceOver/SR harness already covered by R1/R2 XCUITests.
- Final: TBD after `make ci-local` (running total preserved).

## NOTES

### Design judgement — agent-team deliberation 2026-05-20

Three voices (architect / code-reviewer / knitter) considered options A1 / A2 / B / C per the worker prompt:

- **A1**: `extension SymbolDefinition { func localizedLabel(locale: String) -> String }` — function form, Y3 Kotlin signature symmetry, call site passes `Locale.current...` explicitly. Unit-testable in isolation.
- **A2**: `extension SymbolDefinition { var localizedLabel: String }` — computed property, matches `UgcReportCategory+Localized.swift` / `ErrorMessage+Localized.swift` pattern but reads `Locale.current` internally → not unit-testable without iOS test runtime.
- **B**: Call Kotlin top-level fn directly from Swift (`LocalizedLabelKt.localizedLabel(def, "ja")`) — DRY-perfect but `*Kt` prefix is ugly at 4 Swift call sites.
- **C**: Promote Kotlin top-level fn to member fun on `SymbolDefinition` → Swift sees `def.localizedLabel(locale:)` natural. Breaking change to API location introduced just 1 commit ago — rejected.

**Decision**: **A1**. Bridging-layer Swift extension. Signature `func localizedLabel(locale: String) -> String`. Predicate `locale.lowercased().hasPrefix("ja") ? jaLabel : enLabel` mirrors Y3 Kotlin's `startsWith("ja", ignoreCase = true)` exactly (forward-safe vs the existing site `== "ja"` strict equality; iOS `Locale.current.language.languageCode?.identifier` returns ISO 639-1 short code so behavior is currently identical, but accepts BCP-47 `ja-JP`-style input correctly if API changes). 4 call sites resolve `Locale.current.language.languageCode?.identifier ?? "en"` at site and pass into the helper. Mirror extension on `SymbolCategory` added for symmetry with Y3 Kotlin even though no current Swift consumer (forward-compat for future `Text(category.enLabel)` site ports).

**Trade-off accepted**: slight pattern divergence from existing Bridging+Localized convention (function-form vs computed-property-form). Justification: Y3 Kotlin signature symmetry > Bridging-internal pattern consistency; the existing `UgcReportCategory.localizedLabel` doesn't take a `locale` param because it routes through `NSLocalizedString` (iOS String Catalog) which auto-resolves locale, but `SymbolDefinition` carries the two label strings inline so the call site must select which one — making the resolution function-shaped rather than property-shaped is honest about that asymmetry.

### Code-review iteration 2026-05-20

Parallel review (`swift-reviewer` + `code-reviewer` agents) — no CRITICAL / HIGH findings.

- **swift-reviewer MEDIUM**: `SymbolCategory.localizedLabel(locale:)` has no current Swift caller — YAGNI concern. **Action**: kept for Y3 Kotlin signature symmetry per Decision rationale; doc comment already calls out the forward-compat reason (`SymbolGalleryScreen.swift` port). The Y3 Kotlin half also added the symmetric `SymbolCategory` extension with no Kotlin caller — Swift side mirrors that exactly. No fix.
- **swift-reviewer LOW#1**: `let locale` placement asymmetry between `ChartComparisonAccessibility` / `ChartEditorAccessibility` (guard body) and `ChartViewerAccessibility` (with explicit hoist comment). **Verified**: all 3 files place `let locale` at the same hierarchy — `if let rect = ...` guard body, before `ZStack` (= outside `ForEach`, so evaluated once per overlay layout). Consistent. The extra hoist-rationale comment in `ChartViewerAccessibility` is justified because that site needed an explicit pull out of the `ForEach` body (the original `Locale.current` read was inline inside the per-row closure); the other 2 sites already had the `let symbolNameResolver` declared at the post-`descriptors` level, so `let locale` slotted in naturally there. No fix.
- **swift-reviewer LOW#2**: Predicate semantic equivalence verified between `lowercased().hasPrefix("ja")` and Kotlin `startsWith("ja", ignoreCase = true)`. No fix.
- **swift-reviewer LOW#3**: Naming-convention divergence (`func localizedLabel(locale:)` vs sibling `var localizedLabel`) already documented in the extension doc comment. No fix.
- **code-reviewer MEDIUM**: Task file Status still `IN_PROGRESS`, `Result summary` and `TASK RESULT` block unfilled. **Action**: deferred per worker workflow — these fields are populated post `make ci-local` green + post `git push origin/main` (so they can include the pushed SHA + final test count). Not a code defect; sequencing artifact.

Verdict: APPROVED. Safe to invoke `make ci-local`.

### `make ci-local` run-by-run triage 2026-05-20

Local invariant chain ran 4 times. Code green established by run #1; runs #2–#4 surfaced parallel-worktree iOS Simulator state-contamination flake patterns matching CLAUDE.md tech-debt X1 surface ("iOS Simulator state contamination protocol" + "Transient macOS-runner flakes"). Y3-iOS code changes are visual-only refactor; none of the failing tests across runs #2–#4 exercise the 4 modified accessibility surfaces (palette cell label / chart overlay symbol-name resolver).

| Run | Env mods | Outcome | Failing tests |
|---|---|---|---|
| #1 11:34–11:40 | (default, no `ANDROID_SERIAL`) | ktlint→compile→shared test→kover→verifyI18nKeys→ios-build→ios-test **`** TEST SUCCEEDED ** Executed 21 tests, 0 failures`**, then `e2e-android` env-cause fail ("adb: more than one device/emulator" — `QV720H7B31` + `emulator-5554` both booted; `e2e/run-android.sh:52` calls `adb install -r` without `-s` flag) | none in ios-test |
| #2 11:43–11:51 | `ANDROID_SERIAL=emulator-5554` | ios-test fail 5 | `ProjectDetailUITests.testDisplaysProjectDetails`, `testIncrementRow_updatesCount`, `ProjectListUITests.testCreateProject_appearsInList`, `testEmptyState_displaysNoProjectsMessage`, `testProjectWithProgress_displaysCorrectly` |
| #3 11:53–12:01 | `ANDROID_SERIAL=…` + iPhone 16 erase (no `bootstatus -b` wait) | ios-test fail 9 | `NavigationFlowUITests.testDeepLink_invalidURL_doesNotNavigate` (← CLAUDE.md known-flake), `testNavigateToActivityFeed_andBack`, `testStartDestination_isProjectList`, `OnboardingUITests.testTappingNextAdvancesThroughAllPagesAndCompletes`, `ProjectDetailUITests.testDecrementButton_existsAndInitiallyDisabled`, `testEmptyNotes_showsPlaceholder`, `testMarkComplete_showsReopenButton`, `ProjectListUITests.testCreateSheet_cancelDismisses`, `testProjectWithProgress_displaysCorrectly` |
| #4 12:05–12:13 | `ANDROID_SERIAL=…` + iPhone 16 full reset with `xcrun simctl bootstatus -b` 35s Data Migration wait | ios-test fail 9 | `IconButtonAccessibilityTests.testChartEditor_undoRedoOverflowButtons_haveAccessibilityLabels`, `NavigationFlowUITests.testDeepLink_invalidURL_doesNotNavigate`, `testNavigateToActivityFeed_andBack`, `testStartDestination_isProjectList`, `ProfileUITests.testDefaultState_displaysProfileScreen`, `ProjectDetailUITests.testDisplaysProjectDetails`, `testIncrementRow_updatesCount`, `ProjectListUITests.testPlusButton_opensCreateSheet`, `testProjectWithProgress_displaysCorrectly` |

**Triage**:
1. Run #1's ios-test `** TEST SUCCEEDED ** Executed 21 tests, 0 failures` over the same `HEAD` (no source edit between runs) is the decisive evidence that code-level ios-test invariant is green.
2. Across runs #2–#4 the failing-test set rotates (the union is 16 distinct tests, the strict intersection across all three is only `testProjectWithProgress_displaysCorrectly`). A code-caused failure would be deterministic, not rotating. Confirmed transient.
3. The 4th run's `IconButtonAccessibilityTests.testChartEditor_undoRedoOverflowButtons_haveAccessibilityLabels` is the only failing test whose name contains "ChartEditor"; but the test name explicitly targets **undo/redo overflow button** a11y labels, entirely separate from the palette-cell / chart-overlay symbol-name resolution scope this task changes.
4. Run #1 e2e-android env-cause fail (`adb install -r` without `-s` on multi-device host) — solved by `ANDROID_SERIAL=emulator-5554` env-var pin from run #2 onward. Partial `make e2e-android` (post #4) installed APK successfully (`Streamed Install Success`) but Maestro 2.5.x flows all failed `Unable to launch app io.github.b150005.skeinly.dev` — package was confirmed present on emulator-5554 via `adb shell pm list packages`. Matches the existing "Maestro 2.5.x iOS driver setup broken on Xcode 26.x" CI-Known-Limitation pattern on the Android side (Maestro version-cause launch failure, not code-cause). 
5. `make e2e-ios` never reached because runs #2–#4 stopped at ios-test; deferred to GitHub Actions CI verification per CLAUDE.md `## Development Workflow` step 9 CI-wait policy.

**Conclusion**: code-level invariant green established by run #1; iOS Simulator + Maestro 2.5.x env-cause flake is CLAUDE.md tech-debt X1 surface scope (not this task's scope). Pushing per CI-wait policy; orchestrator will reconcile with `gh run list` at consolidation. NOT a BLOCKED state — no rebase-conflict, no code-caused red-test, no scope-question.

## Result summary

**Shipped** (commit `fdbc2eb` on origin/main):
- New `iosApp/iosApp/Core/Bridging/SymbolDefinition+Localized.swift` — Swift extensions `SymbolDefinition.localizedLabel(locale:)` and the symmetric (forward-compat, no current Swift caller) `SymbolCategory.localizedLabel(locale:)`, mirroring the Y3 Kotlin half (`shared/.../LocalizedLabel.kt`, commit `d09d68a`). Predicate `locale.lowercased().hasPrefix("ja") ? jaLabel : enLabel` is contract-equivalent to Kotlin `startsWith("ja", ignoreCase = true)` — forward-safe vs the previous strict `== "ja"` (iOS `Locale.current.language.languageCode?.identifier` returns ISO 639-1 short code so behavior is currently identical, but now accepts BCP-47 `ja-JP`-style input).
- 4 inline ternary call-sites consolidated through the helper: `iosApp/iosApp/Screens/ChartEditorScreen.swift:1170-1175` (palette cell a11y), `ChartComparisonAccessibility.swift:67-77` (overlay symbol-name resolver), `ChartEditorAccessibility.swift:96-106` (overlay), `ChartViewerAccessibility.swift:84-99` (overlay; `let locale` additionally hoisted out of per-row `ForEach` body so it evaluates once per overlay layout, not once per row).

**Design decision** (agent-team deliberation 3 voices, recorded in `## NOTES § Design judgement`): Option A1 — Swift extension on `SymbolDefinition` (function-form with `locale: String` parameter) in the bridging layer. Rejected: A2 (computed property, not unit-testable), B (`LocalizedLabelKt.localizedLabel(def, "ja")` Kt-prefix call, poor Swift ergonomics), C (Kotlin extension → member fun, breaking change to Y3 API location).

**Scope cuts**: none. Symmetric `SymbolCategory.localizedLabel(locale:)` kept despite no current Swift caller — mirrors Y3 Kotlin which also added the symmetric `SymbolCategory.localizedLabel(locale:)` with no Kotlin caller; forward-compat for future `SymbolGalleryScreen.swift` port.

**Test delta**: **+0 XCUITest** / **+0 commonTest** / **+0 Deno**. Y3 Kotlin precedent's 14 `commonTest` assertions (`SymbolDefinitionLocalizedLabelTest.kt`) cover the predicate contract; Swift extension is a 1-line mirror of the same predicate with no independent behavior surface. Running total unchanged.

**Review findings landed** (parallel `swift-reviewer` + `code-reviewer` agents, full trail in `## NOTES § Code-review iteration`): 0 CRITICAL / 0 HIGH. MEDIUM: 1 (`SymbolCategory` no current Swift caller — justified, mirrors Y3 Kotlin's symmetric pattern; no fix). LOW: 3 (placement consistency verified across 3 files — false-positive, no fix; predicate semantic equivalence verified; naming-convention divergence already documented in extension doc-comment). All findings cleared before commit.

**Verification run**: `make ci-local` invoked 4 times (full trail in `## NOTES § ci-local run-by-run triage`).
- Run #1: ktlint / compile / shared test / kover / verifyI18nKeys / ios-build all green; `ios-test ** TEST SUCCEEDED ** Executed 21 tests, 0 failures`; `e2e-android` env-cause fail (`adb install -r` without `-s` on multi-device host — `QV720H7B31` + `emulator-5554` both booted). e2e-ios not reached.
- Runs #2–#4 (with `ANDROID_SERIAL=emulator-5554` pinned; #3 + #4 added iPhone 16 erase + `bootstatus -b` Data Migration wait): ios-test rotating-flake (5 / 9 / 9 tests failed, union of 16 distinct tests, strict intersection of only `testProjectWithProgress_displaysCorrectly`). None of the failing tests exercise the 4 modified accessibility surfaces (palette cell label / chart overlay symbol-name resolver). Pattern matches CLAUDE.md tech-debt **X1 surface "parallel-worktree iOS Simulator state contamination protocol"** + existing **"Transient macOS-runner flakes"** CI-Known-Limitation (`testDeepLink_invalidURL` appears in run #3 fail list — directly listed in CLAUDE.md `## CI Known Limitations`).
- Partial `make e2e-android` post-#4: APK install success (`Streamed Install Success`), package confirmed via `adb shell pm list packages | grep skein`; Maestro 2.5.x flows all failed `Unable to launch app io.github.b150005.skeinly.dev` — env-cause (Maestro version + emulator state), matches existing **"Maestro 2.5.x iOS driver setup broken on Xcode 26.x"** Known-Limitation pattern on the Android side. Not code-cause.
- Triage conclusion: run #1's ios-test SUCCESS + all-front-stage green is the decisive code-level invariant evidence (no source edit across runs; deterministic failure would not rotate). Runs #2–#4 transient flake + e2e-android env-cause are CLAUDE.md tech-debt X1 surface scope, not this task's scope. CI-wait policy (`## Development Workflow` step 9) applied: pushed and proceed; orchestrator reconciles with `gh run list` at consolidation.

**Pushed SHA**: `fdbc2eb` on `origin/main` (refactor code + Bridging extension). This Y3-iOS task file + Status: READY_FOR_CONSOLIDATION ships in the follow-up `docs(phase)` commit per Y3 Kotlin precedent (`9b3c2d0`).

## Follow-ups

- **No new tech-debt surfaced by this task's scope.** The 4-call-site `Locale.current.language.languageCode?.identifier ?? "en"` repetition was considered for further DRY extraction (e.g. a `Locale.currentBCP47LanguageCode()` helper) and judged YAGNI per agent-team deliberation — each site is a function boundary or immutable `let` before a closure with clearly local scope.
- **Re-surfaced existing tech-debt** observed during `make ci-local` triage (orchestrator may want to attach a re-check date to these in `tech-debt.md` at consolidation):
  - CLAUDE.md tech-debt **X1 surface "parallel-worktree iOS Simulator state contamination protocol"** — observed live during this task (Project / Navigation / Onboarding / Profile test rotating flake across 4 sequential ci-local runs in a single worktree, despite iPhone 16 erase + `bootstatus -b` wait; suggests cross-worktree contamination via other concurrent Claude sessions sharing the Makefile-default `iPhone 16` simulator UDID — `xcrun simctl list devices booted` showed iPhone 16 + iPhone 17 both booted with no parallel-session boundary). Confirms the operator-decision triggers in the existing trail.
  - CLAUDE.md ci-known-limitations **"Maestro 2.5.x iOS driver setup broken on Xcode 26.x"** Android-side analog — confirmed via `Streamed Install Success` followed by `Unable to launch app` across all 9 flows with correct `APP_ID`.
  - `e2e/run-android.sh:52` `adb install -r` without `-s` flag — observed in run #1; resolved at the worker level with `ANDROID_SERIAL` env-var pin but would benefit from a Makefile-level multi-device guard. Could be folded into the existing **"X1 surface — Makefile L9 iOS Simulator hardcoded dependency + parallel-worktree iOS Simulator state contamination protocol"** entry in `tech-debt.md` (which already lists Makefile L9 as a candidate site for per-worktree UDID provisioning).

## Task Result (orchestrator-consumed handoff block)

<!-- Emit this verbatim as the LAST thing the worker outputs. -->

```
TASK RESULT
TASK-ID: Y3-iOS
STATUS: READY_FOR_CONSOLIDATION
FILES CHANGED:
iosApp/iosApp/Core/Bridging/SymbolDefinition+Localized.swift
iosApp/iosApp/Screens/ChartComparisonAccessibility.swift
iosApp/iosApp/Screens/ChartEditorAccessibility.swift
iosApp/iosApp/Screens/ChartEditorScreen.swift
iosApp/iosApp/Screens/ChartViewerAccessibility.swift
docs/en/phase/tasks/Y3-iOS.md
WRITE-SET RESPECTED: YES
TEST RESULTS: ios-test ** TEST SUCCEEDED ** Executed 21 tests, 0 failures in run #1 (decisive code-level invariant evidence). Runs #2-#4 rotating transient flake matching CLAUDE.md tech-debt X1 surface (16 distinct tests across runs, strict intersection only testProjectWithProgress_displaysCorrectly, none touching the 4 modified accessibility surfaces). e2e-android env-cause fail (Maestro 2.5.x "Unable to launch app" with package confirmed installed; matches existing Maestro Known-Limitation pattern). e2e-ios not reached locally; deferred to GitHub Actions CI per CI-wait policy. Running total unchanged.
I18N FRAGMENT: none
PUSHED: fdbc2eb on origin/main (refactor code + Bridging extension); this docs(phase) commit follows.
REVIEW: all CRITICAL→LOW landed (0 CRITICAL / 0 HIGH / 1 MEDIUM justified, no fix needed / 3 LOW verified as false-positive or already-documented).
RECOMMENDATION: SHIP
NOTES: Visual-only refactor, behavior-equivalent to pre-change ternary. iOS Simulator state contamination prevented full ci-local green in runs #2-#4 but rotating-flake pattern across same HEAD confirms env-cause, not code-cause. Orchestrator should verify the fdbc2eb GitHub Actions CI green at consolidation; if any required check goes red on a non-this-task surface, attach to the existing X1 surface tech-debt entry.
```
