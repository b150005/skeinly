# R1b — Chart Editor accessibility (per-row overlay + in-row adjustable cell cursor)

## Status

`READY_FOR_CONSOLIDATION`

## Scope

Land the Editor sub-slice of ADR-025: an invisible per-row accessibility
overlay on the rect editor `Canvas` whose row label carries position +
symbol-run summary (no progress, since the editor has no project context),
plus an **adjustable cell cursor** per row (TalkBack swipe-up/down /
VoiceOver adjustable-action moves the cursor across columns within the
focused row, announcing the cell). Each row exposes one named custom
action that maps to the existing `ChartEditorEvent.PlaceCell(cursorX,
cursorY)` — the action label flips between "Place &lt;selected symbol&gt;"
and "Erase" based on the current palette state. Both Compose and SwiftUI
position the overlay in the SAME forward `centeredLayout`/`effectiveCell`
content coordinate space M5 established (rect only; polar gated to
Phase 35.2+ identical to R1a). Closes audit row **B3**.

Explicitly OUT of scope (deliberately):

- Polar editor a11y (gated Phase 35.2+ along with polar editor UX itself,
  per ADR-025 §e).
- The R2 icon-label + ChartImage i18n sweep — symbol-name resolution
  reuses the existing `catalog.enLabel` / `catalog.jaLabel` (R1a fallback
  path, see ADR-025 §f). R1b records the cross-dependency in Follow-ups
  but does not block on R2.
- Editing the 3 shared i18n files. Four new editor a11y string keys ship
  in `R1b.i18n.tsv` for orchestrator splice at consolidation; until then
  Kotlin/Swift use an in-source bilingual fallback that selects en/ja
  from the device locale, mirroring the R1a `enLabel`/`jaLabel` symbol
  fallback. This is a deliberate temporary divergence the orchestrator
  cleans up at consolidation (Follow-up 1).
- ADR-025 status / agent-team / decision sections — only the
  `Revision history` block gains an R1b entry (hot-file rule: same ADR
  file revision-history block).
- B4 / Comparison overlay — R1c (next batch).

## Declared write-set

- `shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/chart/ChartAccessibility.kt`
- `shared/src/commonTest/kotlin/io/github/b150005/skeinly/domain/chart/ChartAccessibilityTest.kt`
- `shared/src/commonMain/kotlin/io/github/b150005/skeinly/ui/chart/ChartEditorScreen.kt`
- `iosApp/iosApp/Screens/ChartEditorScreen.swift`
- `iosApp/iosApp/Screens/ChartEditorAccessibility.swift`  *(new)*
- `.claude/docs/spec/chart-editor.md`
- `docs/en/adr/025-chart-canvas-accessibility.md`  *(Revision history block only)*
- `docs/ja/adr/025-chart-canvas-accessibility.md`  *(Revision history block only)*
- `docs/en/phase/tasks/R1b.md`
- `docs/en/phase/tasks/R1b.i18n.tsv`

## ADR / Spec refs

- ADR-025 §c (Editor row + adjustable cursor), §d (overlay mechanism on
  the M5 coordinate space, Invariant 8 spirit), §f (R2 symbol-name
  cross-dependency, en/ja fallback), §g (test strategy — exhaustive
  commonTest).
- `.claude/docs/spec/chart-editor.md` — add the editor-a11y invariant
  alongside the M5 invariant (single coordinate space, additive overlay).

## Test delta

Final: +14 `ChartAccessibilityTest` cases (`Editor cell-cursor descriptor`
section): exact cursor → 1-based col/row mapping with non-zero
`minX`/`minY` offset, clamp at col 1 / col N / row 1 / row N, topmost
visible at cursor (mirrors `topmostLayerAt`), hidden + invisible layer
exclusion at cursor, blank cell ⇒ null `symbolIdAt`, degenerate extents
⇒ null descriptor, `spokenCellLabel` symbol vs blank vs resolver-fallback
formatting, `placeOrEraseActionLabel` `selectedSymbolId == null` vs
non-null path. **35 ChartAccessibilityTest green** total (21 R1a + 14 R1b).

## Result summary

**What shipped:**

- Pure shared editor a11y API on `ChartAccessibility` —
  `CellAccessibilityDescriptor` (chartX/Y, 1-based colNumber/rowNumber,
  colCount/rowCount, `symbolIdAt`); `CellA11yStrings` (4-field localized
  templates using the shared `%1$d`/`%5$s` substitution syntax);
  `cellDescriptor(rect, layers, hiddenLayerIds, cursorX, cursorY)` with
  clamp to `[minX..maxX] × [minY..maxY]` + topmost-visible at cursor
  identical to `rowRuns`/`topmostLayerAt`; `spokenCellLabel(...)`
  byte-identical to both platforms; `placeOrEraseActionLabel(...)`
  flipping the spoken action label between "Place &lt;symbol&gt;" and
  "Erase" via `selectedSymbolId`.
- Compose `RectEditorAccessibilityOverlay` (private) — per-row invisible
  `Box` siblings of the visual `Canvas` inside the same content-sized
  Box (single coordinate space, no inverse transform); `semantics` carry
  `contentDescription` (row spoken label), `stateDescription` (cell
  spoken label that re-announces on cursor move), `traversalIndex` (row-1
  first), `progressBarRangeInfo` + `setProgress` (adjustable cursor),
  `customActions` (place/erase via `PlaceCell(cursorX, chartY)`).
  `mutableStateMapOf<Int,Int>` cursor map keyed by chart-y, remember-scoped
  to `extents` so a resize wipes stale cursors. The Canvas keeps
  `testTag("editorCanvas")` (Maestro `P1_chart_editor.yaml` landmark
  preserved by construction — testTag-only Canvas with no role / click
  semantic is not TalkBack-focusable, R1a precedent).
- SwiftUI `ChartEditorAccessibilityOverlay` (new file
  `iosApp/iosApp/Screens/ChartEditorAccessibility.swift`) + private
  `EditorRowAccessibilityCell` — per-row `Color.clear` cells with
  `.accessibilityElement` / `.accessibilityLabel` (row) /
  `.accessibilityValue` (cell) / `.accessibilitySortPriority` (row-1
  first) / `.accessibilityAdjustableAction` (cursor) /
  `.accessibilityAction(named:)` (place/erase). `@State [Int32: Int32]`
  cursor reset via `.id(rect)`. Mounted as
  `.overlay(alignment: .topLeading)` of the editor `Canvas` inside the
  same `ScrollView` content frame; the inner Canvas gets
  `.accessibilityHidden(true)` so semantics come solely from the
  overlay.
- `.claude/docs/spec/chart-editor.md` — new Invariant 9 (editor a11y
  additive overlay, same M5 content space, no inverse transform); file
  map updated to mention `RectEditorAccessibilityOverlay` + the new iOS
  file.
- ADR-025 EN + JA `## Revision history` — 2026-05-18 R1b entry (counts,
  mechanism, implementation-time refinements: (i) single
  place-or-erase action mirroring the VM's existing
  `selectedSymbolId == null` → erase route; (ii) `rowDescriptors(...
  progressAt = null)` reuse so editor row label omits the state section
  by construction; (iii) bilingual in-source en/ja fallback for the 4
  new R1b strings via R1b.i18n.tsv → orchestrator splice at
  consolidation).

**Key design decisions** (decisions unchanged from ADR-025 §c–§g; only
mechanism refinements):

1. **One place-or-erase action, not two.** The VM already routes
   `selectedSymbolId == null` to immediate erase, so a single action
   whose label flips is the faithful mirror of the touch affordance —
   simpler API surface + zero behavioural divergence.
2. **`progressAt = null` reuse** — the editor has no project context,
   so the existing `rowDescriptors` lambda contract naturally yields
   "no progress section" by construction; no editor-only row formatter
   needed.
3. **Bilingual in-source fallback for the 4 new keys**, not direct edits
   to the 3 shared i18n files. Protocol-compliant: R1b.i18n.tsv is the
   canonical record for the orchestrator's consolidation splice; the
   in-source en/ja fallback keeps `make ci-local` green locally and on
   CI without violating the i18n-fragment write-set rule.

**Scope cuts** (deliberate / what / why / when-revisit):

- Polar editor a11y — gated to Phase 35.2+ in lockstep with the polar
  editor UX itself (ADR-025 §e + M5 polar-zoom deferral). Revisit with
  the rest of polar UX.
- R2 (icon-label + ChartImage i18n sweep) symbol-name localization —
  R1b uses the existing `catalog.{jaLabel,enLabel}` resolver path,
  falling back to the symbol id for any not-yet-localized symbol
  (ADR-025 §f). Picks up automatically when R2 lands; no R1b code
  change required.
- B4 (Comparison) — R1c (next batch). R1b only closes audit B3.

**Review findings landed**: none above NOTE — agent-team deliberation
(planner / implementer / a11y) accepted the design verbatim; the only
implementation-time refinements are the three items in the ADR-025
revision history (all decisions unchanged, mechanism-level only).

**Verification run**: `IOS_SIM_DEST="platform=iOS Simulator,name=iPhone 17"
make ci-local` — green end-to-end. Chain output:

- `:shared:ktlintCheck` + `:androidApp:ktlintCheck` ✅
- `:shared:compileTestKotlinIosSimulatorArm64` ✅
- `:shared:testAndroidHostTest` ✅ (35 `ChartAccessibilityTest` green)
- `:shared:koverVerify` ✅
- `verifyI18nKeys` ✅ (R1b adds NO new key to the 3 shared files;
  orchestrator splices `R1b.i18n.tsv` at consolidation)
- `make ios-build` ✅
- `make ios-test` ✅ (19 tests / 264s)
- `make e2e-android` ✅ (9/9 incl. `P1 - Chart Editor`)
- `make e2e-ios` ✅ (5/5)

Pushed SHA: see the `TASK RESULT` block emitted as the final message.

## Follow-ups

- **i18n splice**: orchestrator splices `R1b.i18n.tsv` (4 keys) into the
  3 shared i18n files at consolidation. A subsequent commit can then
  drop the in-source en/ja fallback in `ChartEditorScreen.kt` /
  `ChartEditorAccessibility.swift` and switch to
  `stringResource(Res.string.a11y_editor_*)` /
  `NSLocalizedString("a11y_editor_*", …)`. Mirrors R1a's R2 cross-dep
  handling — minor, surfaces no user regression because the en/ja
  fallback is functional.
- R2 (icon-label + ChartImage i18n sweep) is not blocked by R1b. Once R2
  localizes the symbol catalog, the editor cursor announcement
  automatically picks up the localized name via the existing
  `catalog.{jaLabel,enLabel}` resolver path.

## Task Result (orchestrator-consumed handoff block)

*(emitted as the last message at READY_FOR_CONSOLIDATION)*
