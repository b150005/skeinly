# Phase 34 — Per-Segment Progress — PRD

Related: [ADR-010](../adr/010-per-segment-progress.md)

## Problem statement

Knit Note today tracks progress at two levels:

- **Row level** (Phase 1–1.5) — `Project.currentRow` advances an integer;
  `ProgressEntity` logs per-row notes and photos.
- **Chart level** — none. The structured chart (Phase 29–32) is a
  static document. A user looking at a cable pattern cannot mark "I
  finished stitches 1–8 of row 12" without dropping back to the row
  counter and a freeform note.

This gap weakens the Phase 31/32 investment: the visual chart exists
but does not participate in the progress loop. Users must mentally
overlay "where am I" on a static diagram.

Phase 34 closes the loop. A user opening a structured chart should see
their own past work visually highlighted, and a tap should advance the
state of the stitch under their finger.

## Users

- **Primary**: an individual knitter working a pattern with a linked
  structured chart on one or two of their own devices.
- **Secondary (future)**: a knitter receiving chart progress from a
  forked public pattern (Phase 36). Out of scope for Phase 34; must not
  be architecturally ruled out.
- **Not in scope**: multi-writer scenarios (two users marking the same
  chart simultaneously). That's Phase 37+.

## Value hypotheses

1. A user who can mark stitches on the chart itself will abandon the
   row counter faster on cable / colorwork patterns where row-integer
   is insufficient.
2. Visible per-stitch progress reduces "where was I?" friction when
   resuming after interruption — the #1 pain point in freeform
   knitting.
3. Reset-per-project (not per-chart) means a user can re-use a pattern
   for a second project without losing the first project's progress.

We will not instrument these hypotheses in Phase 34; they inform
design, not acceptance.

## User stories

### US-1 (P0): See my segment progress on the chart

As a knitter with a project linked to a structured chart,
when I open the chart viewer,
I can see which stitches I have marked `done` (filled style) and
`wip` (outlined highlight) distinct from untouched stitches.

### US-2 (P0): Tap to toggle stitch state

As a knitter looking at the chart viewer,
when I tap a stitch cell,
its state cycles `todo → wip → done → todo`.
The change persists across app restarts and to my second device.

### US-3 (P0): Long-press to mark done

As a knitter who knows a whole section is finished,
when I long-press a stitch,
it jumps directly to `done` regardless of its prior state.

### US-4 (P0): Reset my progress for this project

As a knitter who wants to re-knit the same pattern,
when I open the project detail screen and tap "Reset progress,"
I get a confirmation dialog; confirming clears all segment state for
this project only. My row-counter journal (`ProgressEntity` notes and
photos) is untouched.

### US-5 (P1): Resume with visual cue

As a knitter reopening a project,
when the chart viewer loads, the first `wip` segment is centered in
the viewport at the current zoom level. If no `wip` exists, the first
`todo` in reading order is centered. If the whole chart is `done`, the
viewport stays at the last-used pan/zoom.

### US-6 (P1): Sync to second device

As a knitter who uses phone + tablet,
when I mark a stitch on one device,
the other device reflects the change within 5 seconds under normal
connectivity without a manual refresh.

### US-7 (P2): Progress summary on project detail

As a knitter reviewing the project,
when I open project detail,
I see "X of Y stitches completed" next to the existing row counter.
Tapping the summary deep-links to the chart viewer.

### Out of scope — explicitly deferred

- **Mark-row-done batch op.** Tapping a row header marks every stitch
  in that row `done`. Phase 35 (advanced editor + batch ops).
- **Progress export / share.** A knitter snapshot-sharing "here's where
  I am." Phase 36.
- **Undo/redo of progress toggles.** Out of product scope — progress
  is "what I did," not a document edit history.
- **Polar chart segments.** The tap target math and overlay style
  differ; Phase 35 houses polar editor + polar progress.
- **Partial-stitch states.** "Half-done cable crossing" is not a real
  knitting state; three states match the craft.
- **Per-segment notes.** Segment-level memos ("I dropped a stitch on
  this one, look back at it") would need a second table. The row-level
  `ProgressEntity` journal remains the memo surface in Phase 34.

## Acceptance criteria

Group each criterion by user story. Numbered for traceability in
commit messages + test names.

### US-1 — Segment overlay render

- **AC-1.1** For any `StructuredChart` with `coordinateSystem =
  RECT_GRID`, the viewer Canvas paints a `done` segment with a filled
  cell background at the theme's `onSurface` color at 20% opacity,
  and a `wip` segment with a 2dp outline stroke at the theme's
  `primary` color. Untouched segments have no overlay.
- **AC-1.2** Overlay paints *under* the symbol glyph (so glyph strokes
  remain legible).
- **AC-1.3** Overlay respects `ChartLayer.visible`. Invisible layers'
  segment rows remain in storage but do not paint.
- **AC-1.4** When `CoordinateSystem = POLAR_ROUND`, the viewer paints
  no segment overlay and shows an inline notice "Segment progress for
  round charts ships in Phase 35" in the chart's top-bar overflow
  menu (not blocking other interactions).

### US-2 — Tap cycles state

- **AC-2.1** A single-tap on a cell with no existing segment row
  inserts a row with `state = wip`.
- **AC-2.2** A single-tap on a `wip` segment transitions it to `done`
  (row updated).
- **AC-2.3** A single-tap on a `done` segment deletes the row (state
  returns to implicit `todo`).
- **AC-2.4** Tap hit-testing is identical to the Phase 32 editor's
  `GridHitTest` — screen → grid with y-flip. No separate tap
  implementation.
- **AC-2.5** A tap on an empty cell (no `ChartCell` at those grid
  coordinates) is a no-op. Progress only applies to drawn stitches.
- **AC-2.6** A tap on a cell whose `ChartLayer.visible = false` is a
  no-op. The overlay does not paint invisible layers; neither does
  the tap advance their state.

### US-3 — Long-press → done

- **AC-3.1** Long-press (≥500ms) on any cell with a `ChartCell` sets
  its segment state to `done`. Idempotent if already `done`.
- **AC-3.2** Long-press on an empty cell is a no-op.
- **AC-3.3** Long-press emits one haptic tick on platforms where the
  framework provides it (Android `HapticFeedbackConstants.LONG_PRESS`,
  iOS `UIImpactFeedbackGenerator.impactOccurred`).

### US-4 — Reset project progress

- **AC-4.1** Project detail screen shows a "Reset progress" destructive
  action under a confirmation dialog. The action is enabled only when
  the project has a linked `StructuredChart` and at least one segment
  row exists.
- **AC-4.2** Confirming deletes every `ProjectSegmentEntity` row with
  `project_id = <this>`. The `ProgressEntity` journal (row notes +
  photos) is untouched.
- **AC-4.3** Reset triggers one Realtime DELETE per row, so a second
  device receives the reset.
- **AC-4.4** Reset is offline-tolerant. The local rows are deleted
  immediately; `PendingSync` queues DELETE operations; the UI reflects
  reset without waiting for network.

### US-5 — Resume viewport

- **AC-5.1** Chart viewer `onEnter` resolves first `wip` (by
  `updatedAt DESC`, tie-break by reading-order). If none, first `todo`
  in reading order.
- **AC-5.2** If the resolved segment exists, the viewer pans so the
  segment's cell center is at the viewport center; zoom stays at the
  last-used value (or default if first open).
- **AC-5.3** If no `todo` remains (whole chart done), the viewer uses
  the last-used pan/zoom.
- **AC-5.4** Resume calculation runs once per `onEnter`, not on every
  segment-state flow emission.

### US-6 — Second-device sync

- **AC-6.1** A segment state change on device A appears on device B's
  open chart viewer within 5 seconds under normal connectivity (using
  the existing Realtime infrastructure latency baseline).
- **AC-6.2** If device B is offline, the segment state change is
  delivered when it reconnects, via the same initial-load fetch that
  the existing Realtime reconnection flow uses.
- **AC-6.3** Segment rows do not leak across users. User A marking a
  segment in their `Project` has no effect on User B's `Project` even
  when both projects reference the same public `Pattern`.

### US-7 — Project detail summary

- **AC-7.1** Project detail shows "X / Y stitches (Z%)" where:
  - `X` = count of segments with `state = done`
  - `Y` = count of `ChartCell` entries across visible layers
  - `Z` = `round(X / Y * 100)`; `0 / 0 = 0%`
- **AC-7.2** Summary recomputes on segment row changes (existing
  observe-flow pattern). No debouncing in Phase 34 — acceptable for
  chart sizes up to Phase 34's test ceiling (2000 cells).
- **AC-7.3** Tapping the summary deep-links to the chart viewer.

## Non-functional requirements

### NFR-1 — Performance

- Segment overlay paint cost on a 500-cell chart must not exceed 2ms
  on a Pixel 6 / iPhone 13 at 60fps scroll. Measured via the existing
  viewer benchmark harness (if absent, a one-off Compose frame-time
  log is acceptable for Phase 34 acceptance; dedicated bench is a
  follow-up).
- Initial load of segment rows for a 2000-segment project must not
  block the viewer first paint by more than 200ms. Use the existing
  `observe` Flow pattern — segments stream in, overlay paints when
  they arrive.

### NFR-2 — Offline

- Tap / long-press / reset all succeed offline. `PendingSync` carries
  pending operations; reconnect drains the queue via the existing
  `SyncExecutor` path.
- Coalescing: rapid toggle on the same segment collapses to one net
  pending operation via the existing `(entity_type, entity_id)` merge.

### NFR-3 — Data integrity

- `UNIQUE(project_id, layer_id, cell_x, cell_y)` enforced on both
  local and remote. A duplicate insert race (two devices simultaneously
  creating a row) resolves by last-write-wins on `updated_at` — server
  accepts the second UPSERT, first device gets the server's version
  back via Realtime. Acceptable pre-Phase-37.

### NFR-4 — Coverage

- 80% test coverage on new shared code (ADR-008 / Phase 29 standard).
- Kotest-style commonTest cases for: `ProjectSegmentRepository` fake,
  `ToggleSegmentStateUseCase` all three transitions, `ResetProjectProgressUseCase`,
  segment-key deterministic-id helper, and mapper round-trip.
- No new commonTest for renderer overlay — Compose/SwiftUI paint is
  covered by Android instrumented test (`paintSegmentOverlay_rendersWipOutline`)
  and iOS XCUITest (`overlayAppears`).

## Design constraints

- **No new i18n keys without parallel 5-source updates.** The sweep
  discipline from Phase 33.1.* applies. Expect ~6 new keys:
  `action_reset_progress`, `dialog_reset_progress_title`,
  `dialog_reset_progress_body`, `label_segments_completed` (parametric
  `%1$d / %2$d (%3$d%%)`), `message_segment_progress_polar_deferred`,
  and possibly `message_reset_progress_done` (Snackbar).
- **testTags / accessibilityIdentifiers mandatory.** `chartViewer`
  landmark already exists. Add `resetProgressButton`, `segmentOverlay`
  (for Android instrumented test hit), and `segmentCountLabel` on the
  project detail summary.
- **No SwiftUI accessibilityLabel drift.** The overlay itself is
  decorative but the tap target must carry `accessibilityLabel(LocalizedStringKey("…"))`
  describing the segment state so VoiceOver / TalkBack users can
  progress.
- **Coordinate with Phase 33 locale config.** Android per-app locale
  already works (Phase 33.3). JA Maestro flow added in this phase must
  use `id:` selectors throughout; no text-literal assertions.

## Risks and open questions

| # | Risk / question | Mitigation / owner |
|---|---|---|
| R-1 | `contentHash` change semantics — if Phase 37 reshapes `layers[*].cells`, existing segment rows become orphans referencing a coord that no longer has a stitch. | Phase 37 introduces a commit DAG; migration of pinned segments to new commits is a Phase 37 concern. Phase 34 acts as-if cells are stable; orphan rows render no overlay (AC-2.5) and can be harvested later. Documented in ADR-010 §Negative. |
| R-2 | Deterministic-id format leak (`seg:<projectId>:<layerId>:<x>:<y>`). | ID is never user-visible; documented inline in the domain model and the sync executor. No PII exposure. |
| R-3 | Second-device Realtime ordering — INSERT, UPDATE, DELETE events may arrive out of order under network flapping. | Client-side truth model is a `Map<SegmentKey, SegmentState>` where absence = todo. Late DELETE idempotent; late INSERT after DELETE recreates a row, which is the desired semantics. |
| R-4 | Reset-progress hits up to 2000 DELETE operations per project. Does Realtime publication cope? | Existing `ProgressEntity` bulk-delete on project delete hasn't been stress-tested at 2000 rows either. Spike test with 2000 segments before acceptance; if backpressure appears, batch DELETEs through a `DELETE FROM ... WHERE project_id = ?` which emits one publication event. |
| Q-1 | Should the overlay reveal *other users'* progress on a shared / public chart? | **No.** Progress is private per ADR-010. Phase 36 may add an opt-in "see sharer's progress" overlay; not in Phase 34. |
| Q-2 | Does tapping a `wip` segment on a second device cycle it by the peer's current state, or by the local render? | By the local render. Optimistic UI updates on tap; Realtime echoes back. If a race produces inconsistency, last-write-wins on `updatedAt` resolves. |
| Q-3 | Should the chart viewer's existing two-finger pan/zoom still be enabled when an overlay tap gesture is live? | Yes. Phase 32 editor already composes a `pointerInput` block with `detectTapGestures` + `detectTransformGestures`. Same pattern here. |

## Scope-cut signals (phase may trim these if schedule pressure appears)

Listed in order of cut priority — first to go at the top.

1. US-7 (project detail summary). Pure nice-to-have; US-1–US-4 carry the product story on their own.
2. US-5 AC-5.2 (center-on-wip pan). Fallback: no auto-pan, viewer opens at last-used viewport. Progress still visible via overlay.
3. Haptic ticks on long-press (AC-3.3). Zero craft-value if omitted.

US-1 through US-4 and US-6 are the minimum shippable surface. US-5 is P1 because absent auto-pan the viewer still shows progress — it just requires manual scrolling.

## Definition of done

- All P0 user stories pass acceptance criteria on both Android and iOS,
  verified by Maestro (Android en-US + ja-JP, iOS en + ja).
- Unit + repository coverage ≥80% for new shared code.
- Code review APPROVED (no CRITICAL / HIGH open).
- ADR-010 landed and referenced from CLAUDE.md Development Roadmap.
- Pre-push invariants green: `ktlintCheck`, `compileTestKotlinIosSimulatorArm64`,
  `testAndroidHostTest`, `koverVerify`, `verify-i18n-keys.sh`.
- CI green on push.

## References

- [ADR-010: Per-segment progress data model](../adr/010-per-segment-progress.md)
- [ADR-008: Structured chart data model](../adr/008-structured-chart-data-model.md)
- [ADR-007: Pivot to chart authoring](../adr/007-pivot-to-chart-authoring.md)
- CLAUDE.md Phase 34 entry
