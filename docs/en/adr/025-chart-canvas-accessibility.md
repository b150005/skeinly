# ADR-025: Chart-Canvas Accessibility — Per-Row Semantic Representation

## Status

Accepted (2026-05-17). Gates the **R1** remediation slice of the
[a11y label-coverage audit](../../../audits/a11y-label-coverage-2026-05-17.md)
(§4). R1 is the single highest-leverage accessibility fix: it unblocks
**two** App Store Connect declarations (**VoiceOver** + **カラー以外で区別 /
Differentiate Without Color**) across **three** screens (Chart Viewer,
Chart Editor, Chart Comparison) on **both** platforms. This ADR fixes the
*semantic model*; implementation lands as sub-slices R1a/R1b/R1c (see
§Sub-slice plan). Polar-grid accessibility is gated to Phase 35.2+ on the
same boundary M5 used (rect-first), so this ADR's normative scope is the
rect grid; the model is defined to extend to polar without redesign.

This ADR does **not** cover the other audit remediation rows (R2 icon-label
+ ChartImage i18n sweep, R3 heading semantics, R4 ProjectDetail Dynamic
Type, R5 state-not-color polish) — those are mechanical and need no
decision record. It also does not cover contrast-ratio measurement
(audit prerequisite (d), still open).

## Context

The 2026-05-17 accessibility label-coverage audit found that Skeinly's
non-chart surface is largely accessible (good `LiveSnackbarHost` /
`AccessibilityAnnouncements` infrastructure, filter chips use non-color
selected cues, status badges render text), but the **chart subsystem — the
reason the app exists — is completely opaque to VoiceOver/TalkBack on both
platforms**:

- **B1 — Chart Viewer** (`shared/.../ui/chart/ChartViewerScreen.kt:495-612`
  / `iosApp/.../Screens/ChartViewerScreen.swift:319-420`): the whole chart
  is one tappable/transformable `Canvas` with **no** `Modifier.semantics` /
  `.accessibilityElement`. A screen-reader user gets an unlabeled,
  uninteractable rectangle for the core read/track task.
- **B2 — Viewer progress is color-only**
  (`ChartViewerScreen.kt:457-465` / `.swift:644-645`): done = fill @20%,
  wip = 2 dp/accent stroke; no text/`accessibilityValue` equivalent.
- **B3 — Chart Editor** authoring `Canvas`
  (`ChartEditorScreen.kt` `RectEditorCanvas` / `.swift:476-579`
  `EditorCanvasView`): tappable place/erase with zero a11y — authoring is
  impossible by screen reader. (Touch-target is M5-resolved; this is the
  orthogonal *perceive/operate* gap.)
- **B4 — Chart Comparison** diff `Canvas`
  (`ChartComparisonScreen.kt:502-557` / `.swift:321-468`): per-cell diff
  identity is 100 % traffic-light fill (added=green / removed=red /
  modified=yellow) with no text/shape; the aggregate `DiffSummaryRow` is
  partial mitigation but per-cell change is invisible to SR + color-blind.

The audit's declaration verdict: VoiceOver and カラー以外で区別 **cannot be
declared** until these are fixed; they share **one root cause** (the chart
`Canvas` has no semantic representation at all) on both platforms. The
audit explicitly deferred the *how* to an ADR because the central question
— **at what granularity do you expose a 2-D, potentially 256×256, grid to a
linear screen reader so it is both perceivable and operable?** — is a
genuine design decision, not a mechanical fix.

Constraints carried in from existing architecture:

- **M5 / ADR-007 lineage** (chart-editor spec Invariant 8): the rect canvas
  is laid out in a **single content coordinate space** inside a 2-axis
  scroll container; rendering and `GridHitTest.hitTest` share that space
  with **no inverse transform**. Any accessibility layer must respect this
  invariant — it cannot reintroduce a transform or a second coordinate
  space.
- **Shared-geometry precedent**: `GridHitTest` /
  `PolarCellLayout` / `WcagTargetSize` are pure shared
  (`domain/chart/`) single-sources-of-truth that both platforms call. The
  audit §2 parity table shows every chart gap is symmetric — a per-platform
  hand-rolled a11y model would drift and re-open the declaration gap.
- **Grid scale**: rect `MAX_GRID_DIMENSION = 256` (`ResizeChartDialog`).
  A per-cell semantic node count of up to 256 × 256 = 65 536 is not a
  usable screen-reader surface.
- **Domain truth** (knitter advisor): charts are worked and tracked
  **row-by-row** (flat) or **ring-by-ring** (round). Progress is row/round
  granular; the Viewer already exposes a row-level *mark-row-done*. Within
  a row, knitters scan **symbol runs** ("8 knit, 2 purl, ×4"), not
  individual cells.

## Agent-team deliberation

### (a) Semantic granularity — per-row primary unit (DECIDED)

Options weighed:

- **Per-cell `accessibilityElement`** — maximal precision; every cell a
  focusable node "row R, col C, <symbol>, <state>". **Rejected**: up to
  65 536 swipe stops on a max-size grid is categorically unusable; it also
  does not match how the craft is worked.
- **Whole-grid summary only** — the canvas announces "16×16 chart, 40 %
  done". **Rejected**: perceivable but **not operable** — the editor
  cannot place a symbol at a target, the viewer cannot mark a *specific*
  row, the comparison cannot locate a change.
- **Per-row elements + in-row cell cursor where precision is required**
  (chosen) — each grid row (polar: each ring) is one accessibility element
  whose spoken text carries position + symbol-run summary + progress/diff
  state; an **adjustable cell cursor** is added **only on the Editor**
  (where cell-precise placement is intrinsic). ≤256 stops, matches the
  visual/craft scan unit, operable.

**Decision (a):** the **grid row** (rect) / **ring** (polar) is the primary
accessibility unit. Cell-level access exists only as an *in-row adjustable
cursor* on the Editor surface, never as 65 k standalone nodes.

### (b) Shared model — new `domain/chart/ChartAccessibility.kt` (DECIDED)

A per-platform hand-rolled string builder would drift (audit §2: every
chart gap is symmetric; divergence re-opens the declaration). Mirroring the
`GridHitTest` precedent, the row-descriptor logic is **pure shared Kotlin**.

**Decision (b):** add `shared/src/commonMain/.../domain/chart/ChartAccessibility.kt`
— pure functions that, given `ChartExtents` + `List<ChartLayer>` (+ optional
per-cell progress state for the Viewer, + optional `ChartComparison` diff
for Comparison), produce an ordered `List<RowAccessibilityDescriptor>`.
Each descriptor carries: 1-based row/ring index and count, a run-length
**symbol-run summary** (localized symbol names via the existing catalog,
keyed — *not* English literals), and a **state** field (progress or diff)
expressed as an enum + count, never as a color token. Both platforms
consume identical descriptors. Symbol-name localization reuses the catalog;
where R2 (palette i18n) has not yet localized a symbol the descriptor falls
back to the symbol id (functional, not blocking — see (f) dependency).

### (c) Per-surface semantics (DECIDED)

| Surface | Row element label (spoken) | Actions / adjustability | Fixes |
|---|---|---|---|
| **Viewer** | "Row R of N — <symbol-run summary> — <done / in progress X of M / not started>" | `accessibilityAction` **mark row done** (maps to the existing row-level op); whole-canvas summary as the container label ("16×16, 40 % complete") | B1, **B2** (progress is now spoken text, not color) |
| **Editor** | "Row R of N — <symbol-run summary>" | **adjustable** cell cursor: increment/decrement moves the column within the focused row, announcing "col C — <symbol or empty>"; `accessibilityAction` **place <selected palette symbol>** / **erase** at cursor | B3 (authoring operable without per-cell nodes) |
| **Comparison** | "Row R of N — <K changes: col C added <sym>, col C2 removed <sym>, …>" | none (read-only); aggregate `DiffSummaryRow` already exposed (keep) | B4 (per-cell diff is now spoken text, not traffic-light color) |

**Decision (c):** all state (progress, diff, symbol identity) is conveyed
as **spoken text** in the row element. This is what simultaneously
satisfies VoiceOver *and* カラー以外で区別 — the two blocked declarations
share this one mechanism.

### (d) Rendering integration — invisible per-row semantic overlay on the M5 coordinate space (DECIDED)

The visual `Canvas` stays unchanged (it is the performant renderer). A11y
is layered as an **invisible overlay of per-row regions** positioned with
the **same** `centeredLayout` origin + `effectiveCell` math M5 established
(rect) / `PolarCellLayout` (polar). Because M5's scroll modifiers are
*layout* modifiers, the overlay scrolls with the canvas automatically and
the row regions stay 1:1 aligned to the rendered grid — **no inverse
transform, no second coordinate space** (M5 / chart-editor Invariant 8
preserved).

- **Compose**: a `Column`/`Box` of per-row `Box`es over the `Canvas`
  inside the same scroll content node, each `Modifier.semantics { … }`
  (+ `progressBarRangeInfo` / custom `setProgress`-style action for the
  Editor cursor); the visual `Canvas` gets
  `Modifier.clearAndSetSemantics {}` so it is not double-announced.
- **SwiftUI**: `.accessibilityElement(children: .contain)` on the canvas
  container with an overlaid invisible `ForEach(rows)` of
  `.accessibilityElement()` regions (+ `.accessibilityAdjustableAction`
  for the Editor cursor); the `Canvas` itself `.accessibilityHidden(true)`.

**Decision (d):** invisible semantic overlay aligned to the existing M5
content coordinate space; the visual canvas is marked
hidden/`clearAndSetSemantics` so semantics come solely from the overlay.

**Implementation refinement (R1a, 2026-05-18 — mechanism only, decision
unchanged):** R1a discovered that the chart `Canvas` carries
`testTag("segmentOverlay")` (Compose) / `.accessibilityIdentifier("segmentOverlay")`
(iOS), a load-bearing landmark asserted by `e2e/flows/{android,ios}/P1_per_segment_progress.yaml`.
A blanket `clearAndSetSemantics {}` on the Compose Canvas would strip that
testTag and break the Maestro flow. The decision (semantics come solely
from the per-row overlay; the canvas contributes no announced element) is
preserved, but the *mechanism* is:

- **Compose**: the Canvas keeps its `testTag` and is **not**
  `clearAndSetSemantics`-ed. A testTag-only node with no
  `contentDescription` / `role` / `onClick` (the canvas uses raw
  `detectTapGestures` pointer input, which is not a click *semantic*) is
  not TalkBack-focusable, so it is already not double-announced — the
  decision holds *by construction* without the call. The viewer's
  coordinate space is `computeViewerLayout` (the viewer was never migrated
  to M5's editor `centeredLayout`/`effectiveCell`; M5 was editor-only). The
  overlay is positioned with that forward layout math at base scale, **not**
  inside the viewer's `graphicsLayer`/`transformable` render transform —
  honoring Invariant 8's "no inverse transform, one coordinate space"
  spirit (screen-reader users do not pinch; the base layout is the
  SR-relevant space).
- **iOS**: `.accessibilityHidden(true)` is applied to the **inner Canvas
  only**, never to the `ChartCanvasView` composite that carries the
  `segmentOverlay` accessibilityIdentifier — so the Maestro landmark is
  preserved while the drawn raster contributes no VoiceOver element.

The `requires-supabase` tag means `P1_per_segment_progress` is excluded
from CI and `make e2e-android`/`-ios`; the landmark preservation is a
documented-flow correctness obligation, not a CI-gated one.

### (e) Polar — same model, ring-indexed; gated Phase 35.2+ (DECIDED)

Polar authoring/viewing is itself Phase 35.2+ (M5 gated polar zoom the same
way). `ChartAccessibility` is defined to accept `ChartExtents.Polar`
(ring = row, `stitchesPerRing[r]` = row length, `PolarCellLayout` for
geometry) so no redesign is needed when polar opens, but the **normative,
shipped scope of R1 is rect**. The polar overlay path is gated on
`extents is Polar` and left unimplemented (parallel to M5's polar deferral)
to avoid shipping an untested polar a11y surface ahead of polar UX.

### (f) Sequencing + the R2 cross-dependency (DECIDED)

Order by user value: **R1a Viewer (perceive + track) → R1b Editor (author)
→ R1c Comparison (diff)**. The Editor announcement of the *selected palette
symbol* ideally uses the localized symbol name that **R2** (palette
icon-label + i18n sweep) introduces; R1b does **not block on R2** — it
falls back to the symbol id / `enLabel` until R2 localizes the catalog,
then automatically improves. Record the dependency; do not serialize.

### (g) Test strategy (DECIDED)

`ChartAccessibility` is pure → exhaustive `commonTest` (row indexing,
run-length summary boundaries, progress/diff state mapping, polar
ring-indexing, empty/degenerate extents, off-by-one at row 1 / row N). The
overlay-alignment + action wiring is validated by the existing
`P1_chart_editor.yaml` (must stay green — the overlay is additive,
non-visual) plus new per-platform UI assertions where the harness supports
a11y-tree queries. Parity is asserted by the shared model: identical
descriptors ⇒ identical spoken text by construction.

## Sub-slice plan

### R1a — Shared model + Chart Viewer (rect)

- New `shared/.../domain/chart/ChartAccessibility.kt`:
  `rowDescriptors(extents: ChartExtents.Rect, layers, progress?) :
  List<RowAccessibilityDescriptor>`; `RowAccessibilityDescriptor`
  (index, total, symbolRunSummaryKeys, state). Pure; no Compose/SwiftUI
  imports.
- Exhaustive `ChartAccessibilityTest` (commonTest).
- Compose `ChartViewerScreen` + SwiftUI `ChartViewerScreen`: invisible
  per-row overlay on the M5 coordinate space; canvas
  `clearAndSetSemantics` / `.accessibilityHidden(true)`; row
  `accessibilityAction` → existing mark-row-done.
- i18n: row-label format + state strings (`a11y_chart_row_*`) × en/ja CMP
  + iOS xcstrings; `verifyI18nKeys` parity. (Symbol-run names reuse
  existing catalog keys; new keys only for the row/state framing.)
- Audit B1/B2 → mark **CLOSED (R1a)** in the audit; update audit §5 + the
  CLAUDE.md Phase-40 ASC prerequisite (c) progression.

### R1b — Chart Editor (rect)

- Editor overlay + adjustable in-row cell cursor; `accessibilityAction`
  place/erase at cursor (routes the existing `ChartEditorEvent.PlaceCell`).
- Cursor announcement falls back to symbol id until R2; tests for the
  cursor state machine (clamp at col 0 / col N-1, layer-visibility
  interaction, parametric-symbol pending-input block already in the VM).
- Audit B3 → CLOSED (R1b).

### R1c — Chart Comparison (rect)

- Comparison overlay: per-row change-list spoken text from the shared
  diff; keep the aggregate `DiffSummaryRow`.
- Audit B4 → CLOSED (R1c); audit §5 → VoiceOver + カラー以外で区別 move
  to declarable once R2 (icon labels) + R3 (headings) also land (see audit
  §5 progression table — R1 alone is necessary, not sufficient, for
  VoiceOver; it **is** sufficient for カラー以外で区別).

## Alternatives considered

- **Per-cell semantic nodes (rejected)** — 65 536-node worst case;
  unusable; not craft-aligned. §(a).
- **Whole-grid summary only (rejected)** — perceivable but not operable;
  fails the Editor and the "mark *this* row" Viewer task. §(a).
- **Per-platform hand-rolled a11y strings (rejected)** — guaranteed drift
  vs the audit §2 parity requirement; violates the `GridHitTest`
  single-source-of-truth precedent. §(b).
- **A separate linear "cell inspector" control decoupled from the canvas
  (rejected)** — adds a parallel navigation model users must learn; the
  per-row overlay reuses the spatial model they already see and the
  existing row-level operations. §(a)/(d).
- **Rendering the a11y tree by transforming canvas draw output (rejected)**
  — would reintroduce a coordinate transform; violates M5 / chart-editor
  Invariant 8 (single coordinate space, no inverse transform). §(d).
- **Shipping polar a11y in R1 (rejected/deferred)** — polar UX is itself
  Phase 35.2+; shipping an untested polar a11y surface ahead of polar UX
  repeats exactly the anti-pattern M5 avoided by gating polar zoom. §(e).

## Consequences

- **Positive**: one shared model + an additive invisible overlay unblocks
  two ASC declarations across three screens × two platforms with no change
  to the visual renderer and no violation of the M5 coordinate-space
  invariant. The per-row unit is craft-true and bounded (≤256 nodes).
  Progress/diff become spoken text, structurally killing the color-only
  blockers. Parity is guaranteed by construction (identical shared
  descriptors).
- **Negative / costs**: three sub-slices touching both platforms + new
  shared model + i18n + the full `make ci-local` gate each (with the
  documented Xcode-26 host caveat: `IOS_SIM_DEST=iPhone 17`, ios-test
  teardown + e2e-ios are CI-gated per the A20/A33 precedent). The Editor
  adjustable-cursor is the most intricate piece (state machine + action
  routing) and carries the R2 cross-dependency for symbol naming.
- **Follow-ups**: VoiceOver becomes declarable only after R1 **and** R2
  (icon labels) **and** R3 (headings) — R1 is necessary, not sufficient,
  for VoiceOver (it **is** sufficient for カラー以外で区別). The audit §5
  progression table is the source of truth for declaration timing.

## Revision history

- 2026-05-17 — Accepted. Authored from the a11y label-coverage audit R1.
  Agent-team deliberation (knitter / ui-ux-designer / architect /
  implementer) recorded inline. No code yet; this ADR gates R1a/R1b/R1c.
- 2026-05-18 — **R1a shipped** (Viewer + shared model). New pure
  `shared/.../domain/chart/ChartAccessibility.kt` (`rowDescriptors` +
  `RowAccessibilityDescriptor`/`SymbolRun`/`RowProgress` + `A11yStrings` +
  `spokenLabel`) with 21 exhaustive `commonTest`; Compose
  `RectRowAccessibilityOverlay` + SwiftUI `RowAccessibilityCell` invisible
  per-row overlays; 9 `a11y_chart_*` i18n keys (en/ja CMP + xcstrings,
  `verifyI18nKeys` parity). Implementation-time refinements (decisions
  unchanged): progress passed as a `progressAt` lambda (not a
  `Map<SegmentKey,…>` — avoids the Kotlin/Native Swift-`Hashable` footgun);
  canvas-suppression mechanism per §(d) Implementation refinement (preserve
  the `segmentOverlay` Maestro landmark). Audit B1/B2 → CLOSED (R1a). B3
  (Editor) → R1b, B4 (Comparison) → R1c remain open.
- 2026-05-18 — **R1b shipped** (Editor + in-row adjustable cell cursor).
  Extended `ChartAccessibility` with `CellAccessibilityDescriptor`,
  `CellA11yStrings`, `cellDescriptor(...)` (clamps the cursor to
  `[minX..maxX] × [minY..maxY]`; topmost-visible symbol mirroring
  `rowRuns`/`topmostLayerAt`; `hiddenLayerIds` + `visible` lockstep with
  the row overlay), `spokenCellLabel(...)`, `placeOrEraseActionLabel(...)`
  + 14 new `commonTest` (clamp at col 1 / col N / row 1 / row N,
  topmost-visible at cursor, invisible + hidden layer exclusion, blank
  cell, degenerate extents, format with/without resolver, action label
  both paths) — **35 ChartAccessibilityTest green** total. Compose
  `RectEditorAccessibilityOverlay` (per-row `Box` with
  `progressBarRangeInfo` + `setProgress` for the cursor + `stateDescription`
  for the cell readout + `customActions` for place/erase, all keyed by
  `mutableStateMapOf<Int,Int>` cursor state remember-scoped to extents)
  and SwiftUI `ChartEditorAccessibilityOverlay` (`.accessibilityAdjustableAction`
  + `.accessibilityValue` for the cursor + `.accessibilityAction(named:)`
  for place/erase, `@State [Int32:Int32]` cursor reset via `.id(rect)`).
  The visual Canvas keeps `testTag("editorCanvas")` (Compose) +
  `.accessibilityHidden(true)` (iOS — applied to the inner Canvas only,
  per §(d) Implementation refinement); the overlay carries no pointer
  modifier so touch + scroll still pass to the Canvas for sighted users.
  Implementation-time refinements (decisions unchanged): (i) the
  place/erase action is **one** action whose label flips between
  "Place &lt;symbol&gt;" / "Erase" — both intentionally route to the
  same `PlaceCell(x, y)` VM event because the VM already maps
  `selectedSymbolId == null` to erase, so the spoken action faithfully
  mirrors the touch affordance; (ii) the editor has no project / progress
  context ⇒ R1b reuses `rowDescriptors(... progressAt = null)` so the row
  spoken label omits the trailing state section by construction (matching
  §c "no progress" Editor row); (iii) the 4 new R1b i18n keys
  (`a11y_editor_cell_with_symbol`, `a11y_editor_cell_blank`,
  `a11y_editor_action_place`, `a11y_editor_action_erase`) are shipped via
  `docs/en/phase/tasks/R1b.i18n.tsv` for orchestrator splice at
  consolidation (parallel-worktree i18n-fragment protocol); both
  platforms use an in-source en/ja bilingual fallback selecting by
  device locale — identical pattern to R1a's `enLabel`/`jaLabel` symbol
  fallback. Audit B3 → CLOSED (R1b). B4 (Comparison) → R1c remains open.
- 2026-05-18 — **R1c shipped** (Chart Comparison + per-row change list).
  Extended `ChartAccessibility` with `DiffChangeKind` (`ADDED` /
  `REMOVED` / `MODIFIED`), `RowDiffChange` (1-based `colNumber`, kind,
  symbolId), `RowDiffDescriptor` (1-based `rowNumber` / `rowCount` /
  `chartY` / non-empty `changes` list), `DiffA11yStrings` (7 localized
  templates — 3 reused from R1a `a11y_chart_*`, 4 R1c-new), pure
  `rowDiffDescriptors(targetExtents, cellChanges, layerChanges)` (groups
  `CellChange.Added`/`Removed`/`Modified` + `LayerChange.Added`/`Removed`
  cells by chartY into per-row change lists; skips
  `LayerChange.PropertyChanged` at the cell level matching the renderer's
  existing rule; sorts changes by `colNumber` asc; emits ONLY rows with
  ≥1 change so SR users never swipe through unchanged rows), and
  `spokenDiffLabel(...)` byte-identical formatter for both platforms +
  12 new `commonTest` (cell-change → row mapping per kind, layer-add /
  layer-remove expansion, PropertyChanged ignore, sort-by-col asc, omit
  empty rows, 1-based off-by-one at row 1 / col 1 with offset extents,
  drop changes outside target extents — the shrunken-target case,
  degenerate extents → empty, AFTER-symbol use for Modified, formatter
  composition + null/unresolved-id fallback) — **47 ChartAccessibilityTest
  green** total. Compose `RectComparisonAccessibilityOverlay` (private
  composable in `ChartComparisonScreen.kt` — per-row invisible `Box`
  positioned with the SAME forward `computeDiffLayout` math the visual
  Canvas draws with at base scale, inside a `BoxWithConstraints` exposing
  the target-pane pixel size; `isTraversalGroup` on the parent +
  `traversalIndex = rowNumber` for row-1-first SR traversal) and SwiftUI
  `ChartComparisonAccessibilityOverlay` (new
  `iosApp/iosApp/Screens/ChartComparisonAccessibility.swift`, mirrors
  R1b's `ChartEditorAccessibility.swift`; per-row `Color.clear`
  `.accessibilityElement()` + `.accessibilityLabel` +
  `.accessibilitySortPriority`) wired into `ChartComparisonScreen.swift`
  `DualCanvasPanel` — target pane wrapped in `GeometryReader` + `ZStack`;
  `.accessibilityHidden(true)` applied to the inner `DiffCanvas` only,
  never to the `GeometryReader` carrying the `targetChartCanvas`
  accessibilityIdentifier (§(d) Implementation refinement — Maestro
  landmark preserved). Implementation-time refinements (decisions
  unchanged): (i) the overlay is aligned with the TARGET pane only
  (`diff.base` can be `nil` on initial commit; target is always non-null
  and represents the post-change state — `spokenDiffLabel` narrates a
  unified diff anchored there); (ii) `LayerChange.PropertyChanged` is
  surfaced exclusively by the existing `LayerChangesBanner` and is
  intentionally NOT enumerated at the cell level by the overlay,
  matching the renderer's `classifyCells` rule; (iii) Removed cells
  whose `chartY` falls outside `target.extents` (shrunken-target rare
  case) are silently dropped from the spoken description — they remain
  visible on the base pane only, and tracking them in unified row index
  would force a phantom out-of-bounds row number on the SR user (logged
  as a Follow-up if the audit revisits); (iv) the 4 new R1c i18n keys
  (`a11y_diff_change_added` / `_removed` / `_modified` / `_separator`)
  are shipped via `docs/en/phase/tasks/R1c.i18n.tsv` for orchestrator
  splice at consolidation (parallel-worktree i18n-fragment protocol);
  Compose + SwiftUI both use in-source en/ja bilingual fallback
  selecting by device locale, identical pattern to R1b's
  `makeCellA11yStrings(isJa:)`. Audit B4 → CLOSED (R1c).
  カラー以外で区別 (Differentiate-Without-Color) is now declarable;
  VoiceOver still needs R2 (icon labels) + R3 (headings) per audit §5
  progression table — R1 is necessary, not sufficient, for VoiceOver.
