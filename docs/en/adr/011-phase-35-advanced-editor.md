# ADR-011: Phase 35 Advanced Chart Editor + Polar UX

## Status

Proposed

## Context

Phase 32 shipped the chart editor MVP — tap-to-place authoring with undo/redo,
parametric input, and craft/reading metadata. Phase 34 (ADR-010) added
per-segment progress (todo/wip/done) for rect-grid charts.

Two large UX gaps remain and block the Phase 36+ discovery/collaboration work:

1. **Polar charts are viewable-but-not-editable and progress-less.** The data
   model (`ChartExtents.Polar(rings, stitchesPerRing)`) has been in place since
   Phase 29, but the editor forces `RECT_GRID` and the Phase 34 overlay paints
   an "Coming in Phase 35" notice
   (`message_segment_progress_polar_deferred`) instead of segment state. This
   excludes amigurumi, doilies, and every crochet round chart from the core
   progress loop — a significant portion of the target audience.

2. **The editor has no symmetry or batch ops.** A lace panel with four-way
   symmetry currently requires ~4× the tap-placement work. A knitter finishing
   row 12 of a 28-stitch cable must long-press or double-tap every stitch to
   mark the whole row done. Both are common-enough workflows that the absence
   is a daily papercut, not an edge case.

Three secondary items (layer ops, snap grid, grid-size picker) were flagged
in the Phase 35 roadmap line of CLAUDE.md. This ADR resolves MVP vs. defer
for each.

Constraints carried forward:

- **ADR-008 §7 / Phase 32.1:** `contentHash` covers drawing identity only.
  Symmetry ops mutate `layers` → `contentHash` recomputes (expected). Progress
  is project-scoped (ADR-010) and is not in the hash.
- **ADR-010 §4:** `ProjectSegmentEntity.cell_x / cell_y` carry the same values
  as `ChartCell.x / y` **without reinterpretation**. This ADR makes the polar
  interpretation of those columns explicit — it does not change the column
  semantics, only the convention readers apply.
- **ADR-009:** Parametric symbols are out of scope for mirror semantics —
  their craft meaning is parameter-bound, not geometry-bound.
- **Phase 32 MVP invariants:** `EditHistory` is drawing-only (ADR-008 §7 +
  Phase 32 Completed notes); metadata changes don't take undo slots. Polar
  editing preserves this — toggling polar↔rect at chart-create time is a
  metadata action (doesn't enter history), but cell placements inside a
  polar chart are drawing ops and DO enter history.

## Agent team deliberation

The team was convened once for the full ADR rather than per-section, because
the four topics interact (polar decides how symmetry ops generalise; batch
row-done rides the symmetry-op delivery path; grid-size picker couples to
polar-extent authoring).

### Voices

- **architect:** Polar geometry belongs in a pure helper (`PolarCellLayout`)
  parallel to `cellScreenRect` + `GridHitTest`, not in the renderer. That
  keeps the two renderers (Compose + SwiftUI bridge via `ScopedViewModel`)
  from re-deriving trigonometry and keeps tests pure-Kotlin. Symmetry ops
  belong in a `SymmetryOp` use case operating on `List<ChartLayer>` — viewer
  and editor should be able to preview symmetrised output without mutating
  draft state. Batch row-done should NOT invent a new repository bulk API
  for MVP — reuse the per-segment loop. A bulk API becomes load-bearing
  only when Phase 37 multi-writer merges arrive, and designing it now
  locks in the wrong shape.

- **product-manager:** Polar is the unlock. Without it, "knitters" for this
  product means "flat-only knitters," which cuts out roughly half the JP
  market (amigurumi is massive in JP; doilies + top-down yoke sweaters
  round it out). Symmetry ops are table-stakes for anyone authoring a lace
  chart — defer them and discovery looks like a toy. Batch row-done is a
  quality-of-life fix for the users already using the product. Layer ops +
  grid resize are expected-affordances — users hit them and leave if
  they're missing. Snap grid is a non-goal: the chart IS a grid, there is
  nothing to snap to.

- **knitter:** Polar angle origin MUST be "12 o'clock, clockwise" for JP
  round-chart convention (JIS L 0201 §5 diagrams read this way; Japanese
  amigurumi books universally do). Starting at 3 o'clock counterclockwise
  is a US crochet-textbook convention and would look wrong. Asymmetric
  symbols under horizontal mirror MUST route through a mirror-variant
  lookup — silently geo-flipping a left-leaning cable into a mirror-image
  glyph that doesn't exist in the JIS catalog produces a chart that looks
  plausible but is unworkable. Refuse or prompt, don't guess. For
  rotational symmetry: knit vs. purl swap is NOT a rotation — the bar
  direction is craft-meaningful. Rotating a knit bar 90° does not turn it
  into a purl bump, and treating them as a rotation pair would silently
  corrupt the chart. Mirror pairs are per-axis; don't conflate axes.

- **implementer:** `SymbolDefinition` needs a new optional field
  `mirrorHorizontal: String?` pointing to the mirror-variant id.
  Populate it for cables and directional decreases (k2tog ↔ ssk, etc.);
  leave null for symmetric glyphs (knit bar, purl dot, yarn-over). The
  symmetry-op use case should return a typed result enumerating
  unmirrorable cells so the UI can prompt. Polar hit-test needs to
  account for inner-hole (center) taps: `radius < innerRadius` → null,
  matching the flat `outside-grid = null` convention. iOS SwiftUI gesture
  chain already had a double-fire bug in Phase 34 between `.onTapGesture`
  and `.onLongPressGesture` — polar overlay will hit the same thing, so
  reuse the Phase 34 `longPressActive` suppression flag pattern.

- **ui-ux-designer:** Batch "mark row done" affordance should NOT be a
  separate button on the toolbar — toolbar space is scarce and the
  existing cycle-tap / long-press affordances suggest the interaction
  model. Put it on long-press of the row-number label (visible when row
  numbers are rendered; ADR-008 §5 for numbering convention). For polar,
  the equivalent is long-press on the ring label (rendered along the
  "12 o'clock" diameter for the ring). Symmetry ops live behind a single
  toolbar overflow menu (`MirrorHorizontal`, `MirrorVertical`, `Rotate
  180°`) — each is a one-shot op that writes a new `EditHistory` entry,
  so undo walks back naturally. Grid-size picker ships as a `Resize
  chart` dialog from the overflow menu; cells falling outside the new
  extents are trimmed (not preserved off-grid) with a count shown in the
  dialog before confirm.

### Decision points resolved by the team

1. **Polar angle origin** → 12 o'clock, clockwise (knitter, strong).
2. **Rotational knit↔purl** → NOT a symmetry op (knitter, strong — it's
   craft-meaningful, not geometric).
3. **Mirror variant lookup** → new `SymbolDefinition.mirrorHorizontal: String?`;
   unmirrorable cells surface to UI as a prompt, not silently skipped
   (knitter + implementer agree).
4. **Batch row-done repository shape** → per-segment upsert loop for MVP;
   no new bulk API (architect, strong; product-manager yields).
5. **Snap grid** → non-goal (product-manager; architect agrees).
6. **Layer ops + grid resize** → MVP (product-manager; architect agrees
   with scope caveat below).
7. **Batch row-done affordance** → long-press on row/ring number label
   (ui-ux-designer; implementer notes iOS gesture-conflict reuse).

## Decision

### 1. Polar coordinate convention and transform

`ChartExtents.Polar(rings, stitchesPerRing: List<Int>)` interprets cells
as `(stitch_index, ring_index)`:

- `ChartCell.x = stitch_index` — angular position within the ring,
  `0 ≤ x < stitchesPerRing[y]`.
- `ChartCell.y = ring_index` — radial position, `0 ≤ y < rings`, innermost
  ring is `y = 0`. Y grows outward, matching the "progress grows outward"
  reading of rounds.

This is **a convention, not a data change.** The column types and
`ProjectSegment` storage are unchanged from ADR-010 §4. The document-level
`extents.stitchesPerRing[y]` is the authoritative stitch count for ring
`y`; `ChartCell.width` is ignored for polar (cells are always 1×1 in
polar — spanning wedges is a Phase 35.x follow-up, see §9 below).

New pure helper `domain/chart/PolarCellLayout.kt`:

```kotlin
object PolarCellLayout {
    data class Layout(
        val cx: Double,
        val cy: Double,
        val innerRadius: Double,
        val ringThickness: Double,
    )

    /** Screen-space wedge bounds for cell (x = stitch, y = ring). */
    data class Wedge(
        val innerRadius: Double,
        val outerRadius: Double,
        val startAngleRad: Double,  // 12 o'clock = -π/2; clockwise positive
        val sweepAngleRad: Double,
    )

    fun wedgeFor(
        stitch: Int,
        ring: Int,
        extents: ChartExtents.Polar,
        layout: Layout,
    ): Wedge

    /** Cartesian center of the cell wedge, for symbol glyph placement. */
    fun cellCenter(
        stitch: Int,
        ring: Int,
        extents: ChartExtents.Polar,
        layout: Layout,
    ): Pair<Double, Double>

    /** Rotation (radians) such that "local up" for the cell points radially outward. */
    fun cellRadialUpRotation(
        stitch: Int,
        ring: Int,
        extents: ChartExtents.Polar,
    ): Double
}
```

Hit-test inverse is a new method on `GridHitTest` (not a new object, to keep
the screen→cell call site uniform across rect and polar):

```kotlin
fun hitTestPolar(
    screenX: Double,
    screenY: Double,
    extents: ChartExtents.Polar,
    layout: PolarCellLayout.Layout,
): Cell?
```

Algorithm:

1. `dx = screenX - cx`, `dy = screenY - cy`.
2. `radius = √(dx² + dy²)`.
3. If `radius < innerRadius` or `radius ≥ innerRadius + rings * ringThickness` → return null (inner-hole tap or outside outermost ring).
4. `ring = floor((radius - innerRadius) / ringThickness)`.
5. `theta = atan2(dy, dx) + π/2`, normalised to `[0, 2π)` (shift so 12 o'clock = 0, clockwise positive — matches knitter convention).
6. `stitch = floor(theta / (2π / stitchesPerRing[ring]))`.
7. Return `Cell(x = stitch, y = ring)`.

Symbol rendering inside a polar cell uses the **inscribed square** at
`cellCenter`, rotated by `cellRadialUpRotation + cell.rotation`. This
keeps glyphs undistorted (matches JIS round-chart convention) at the cost
of inner-ring glyphs rendering at a smaller effective size than outer-ring
glyphs. The inscribed square side = `min(ringThickness, 2 * r * sin(π /
stitchesPerRing[ring]))`. Glyph anti-aliasing is a Phase 35.x polish task
if the size disparity is too visible.

### 2. Per-segment progress overlay extends to polar

The Phase 34 overlay logic already iterates `ProjectSegment` rows by
`(layerId, cellX, cellY)`. Polar support is a paint-path and hit-test
swap:

- `ChartViewerScreen` detects `extents is ChartExtents.Polar` and routes to
  `drawPolarSegmentOverlay` (paints a filled wedge for `DONE`, a stroked
  wedge for `WIP`) instead of the flat-rect overlay.
- Tap routes through `hitTestPolar` instead of `hitTest`; everything
  downstream (`ToggleSegmentState`, `MarkSegmentDone`) is unchanged.
- The Phase 34 `message_segment_progress_polar_deferred` notice is removed.

The existing Phase 34 iOS gesture-conflict suppression
(`longPressActive` flag with 0.3s window) is reused verbatim.

### 3. Symmetry / mirror copy editor ops

New `SymmetryOp` use case (not a repository op — symmetry is an editor
transform, not a data primitive):

```kotlin
sealed interface SymmetryAxis {
    data object Horizontal : SymmetryAxis      // flip about vertical axis
    data object Vertical : SymmetryAxis        // flip about horizontal axis
    data object Rotate180 : SymmetryAxis       // H + V
    data class PolarAngular(val aboutStitch: Int) : SymmetryAxis  // mirror across ray
    // Rotate90 / Rotate270 intentionally absent — see §7, ADR-009 §0 on
    // asymmetric-glyph rotation: general rotation is craft-incoherent.
}

class SymmetrizeLayersUseCase(
    private val symbolCatalog: SymbolCatalog,
) {
    data class Result(
        val mirrored: List<ChartLayer>,
        val unmirrorableCells: List<UnmirrorableCell>,  // cells whose symbol has no mirror pair
    )
    data class UnmirrorableCell(val layerId: String, val x: Int, val y: Int, val symbolId: String)

    suspend operator fun invoke(
        layers: List<ChartLayer>,
        extents: ChartExtents,
        axis: SymmetryAxis,
    ): Result
}
```

Behavior:

- **Rect horizontal** (flip about vertical axis): `(x, y) → (maxX - (x - minX), y)`; for asymmetric symbols, look up `SymbolDefinition.mirrorHorizontal`; if null → add to `unmirrorableCells`, preserve original in `mirrored` unchanged.
- **Rect vertical** (flip about horizontal axis): `(x, y) → (x, maxY - (y - minY))`; `rotation` negated (`(360 - rotation) mod 360`).
- **Rect Rotate180**: compose H + V; `rotation = (rotation + 180) mod 360`.
- **Polar angular** (mirror about the ray passing through stitch index `aboutStitch`): `(x, y) → ((2·aboutStitch - x) mod stitchesPerRing[y], y)`; rotation negated.
- Original layers are **preserved** — the UseCase returns a new `layers` list. The editor appends cells from the mirrored version onto a new *target* layer (default) or merges into the source layer (if user opts in). This lets the user keep the original half as a locked layer for reference.

UI flow:

1. User taps toolbar overflow → `Mirror horizontal` (or vertical / rotate180 / angular).
2. For angular: user taps the "mirror ray" on the canvas (highlighted diameter visualisation); stitch index is resolved via `hitTestPolar`.
3. `SymmetrizeLayersUseCase` runs; if `unmirrorableCells` is non-empty, dialog lists them with `(layerId, x, y, symbolId, symbolLabel)` and offers three options: **Skip unmirrorable** (current behaviour of preserving original), **Cancel**, or (future) **Edit mirror pair** (deep-link to a mirror-pair authoring UI — Phase 35.x).
4. On confirm, the draft layers are replaced and an `EditHistory` entry is recorded (standard `HistoryEntry.LayersSnapshot` — symmetry op is one snapshot, undo walks back in one step).

New `SymbolDefinition` field:

```kotlin
data class SymbolDefinition(
    // ... existing fields ...
    val mirrorHorizontal: String? = null,
)
```

Mirror-pair data to populate in Phase 35:

- `jis.knit.cable.2-2-right` ↔ `jis.knit.cable.2-2-left` (and every cable family).
- `jis.knit.k2tog` ↔ `jis.knit.ssk`.
- `jis.knit.k3tog` ↔ `jis.knit.sssk`.
- Crochet cluster slants — knitter to enumerate per JIS L 0201 §6.

Symmetric glyphs (knit bar, purl dot, yarn-over, most crochet basic
stitches) leave `mirrorHorizontal = null`; the use case interprets null as
"symbol is self-symmetric" and does NOT add to `unmirrorableCells`. An
explicit `selfSymmetric: Boolean = false` flag was considered and rejected
(it duplicates the null check with no new information).

Vertical mirror uses no mirror-pair lookup — symbols don't have vertical
pairs in JIS (no "upside-down knit"). Vertical mirror flips the `rotation`
field and is applied to every cell unconditionally.

### 4. Batch "mark row done"

UX affordance: **long-press on the row-number label** (rect) or
**long-press on the ring-number label** (polar). The label region is
painted adjacent to the chart in both renderers (ADR-008 §5 for
convention); Phase 35 adds a hit-target around it.

Implementation path (decision: §1 of the agent team deliberation):

```kotlin
class MarkRowSegmentsDoneUseCase(
    private val repository: ProjectSegmentRepository,
    private val getStructuredChart: GetStructuredChartByPatternIdUseCase,
    // ... plus the existing dispatcher and ownerId deps ...
) {
    suspend operator fun invoke(
        projectId: String,
        row: Int,   // chart y-coordinate (rect) or ring index (polar)
    ) {
        val chart = getStructuredChart(...)
        val visibleCellsInRow = chart.layers
            .filter { it.visible }
            .flatMap { layer -> layer.cells.filter { it.y == row }.map { cell -> layer.id to cell } }
        // Per-segment upsert loop — no new bulk API. See ADR-011 §4 tradeoff.
        visibleCellsInRow.forEach { (layerId, cell) ->
            repository.upsert(
                ProjectSegment(
                    id = ProjectSegment.buildId(projectId, layerId, cell.x, cell.y),
                    projectId = projectId,
                    layerId = layerId,
                    cellX = cell.x,
                    cellY = cell.y,
                    state = SegmentState.DONE,
                    // ...
                )
            )
        }
    }
}
```

**Tradeoff: per-segment loop vs. new bulk API.**

| Axis | Loop (chosen) | Bulk API |
|---|---|---|
| LOC | ~30 in UseCase | ~150 across Repo/DataSource/Remote/Supabase |
| Sync wire shape | N `PendingSync` rows, coalesce by `(entity_type, entity_id)` | 1 `PendingSync` row carrying N-element payload, new coalesce rule needed |
| Offline correctness | Trivial — each segment is independent | Requires transactional apply at replay, or partial-apply semantics |
| Realtime burst on peer | N individual INSERT/UPDATE events | 1 "row update" event, new client-side decode |
| Phase 37 diff-semantics | Each segment diff independently | Bulk payload becomes an opaque blob unless decoded |
| Failure recovery | Per-segment retry via existing `SyncExecutor` | New retry shape for partial payloads |

The bulk API is the right shape when **collaboration burst** patterns
matter — e.g. Phase 37 "team member X marked row 42 done" as one event,
diffable as one unit. For Phase 35 the loop wins on every axis except
sync wire volume, and for a typical row (10–30 stitches) the volume cost
is sub-millisecond on the local DB and invisible over Realtime. A polar
ring can reach 200+ stitches (outer round of a doily); even there, 200
PendingSync rows apply in <100ms locally and coalesce before network if
the user re-fires the op.

The `upsertBatch` bulk API is **deferred to Phase 37** when the diff
shape forces the question. Documented here so it's not rediscovered from
scratch.

### 5. Layer ops

MVP scope:

- Add layer (blank).
- Remove layer (with confirmation if it has cells).
- Rename layer.
- Reorder layers (drag handle on layer-list panel).
- Toggle visibility (`ChartLayer.visible` — already in data model; viewer
  respects it today).
- Toggle lock (`ChartLayer.locked` — already in data model; editor must
  refuse placement on locked layers and grey out layer rows in palette).

All layer ops are `EditHistory` entries (drawing identity changes —
`contentHash` depends on layer order and cell contents).

Layer-list panel UI: right-side drawer, swipable in on both platforms.
Each row shows: drag handle, visibility toggle, lock toggle, name
(tap to rename inline), overflow menu (delete).

### 6. Grid-size picker ("Resize chart")

Entry: editor overflow menu → `Resize chart`.

Dialog content:

- **Rect:** Width + Height number inputs (min 1, max 256 each).
- **Polar:** Rings number input; per-ring stitch count either "uniform
  (apply N to all rings)" or "per-ring list" (advanced, Phase 35.x —
  MVP is uniform only).

Trim behaviour:

- Cells with `x > newMaxX` or `y > newMaxY` (rect) are deleted.
- For polar, cells with `y >= newRings` or `x >= newStitchesPerRing[y]`
  are deleted.
- Dialog shows "N cells will be removed" count before confirm.
- Resize is ONE `EditHistory` entry (including the trim).

Extents shrink OR grow. Grow is free (no trim). Shrink with trim>0
requires explicit confirm.

### 7. Explicitly NOT in Phase 35 MVP

- **Snap grid toggle.** The chart IS the grid. There is nothing off-grid to
  snap to.
- **Arbitrary rotation (90°/270°/free angle).** Most symbol glyphs don't
  have a craft-coherent rotation — a 90°-rotated knit bar is not a valid
  JIS symbol, and the chart viewer would render something that looks like
  it means something but doesn't. Pattern authors who need rotated
  motifs can compose them from rotation-aware parts. Revisit in Phase 36+
  when discovery surfaces demand for rotated import (e.g. hooked-on-side
  tapestry charts).
- **Per-ring variable stitch count editing.** The data model supports it
  (`stitchesPerRing: List<Int>`), but editing it requires a distinct UI
  (per-ring editor). MVP ships uniform rings; imported charts with
  variable rings render correctly.
- **Polar wedge-spanning cells.** `ChartCell.width` is ignored in polar
  for MVP. A stitch that spans 2 angular positions (e.g. a decrease that
  consumes 2 stitches of the prior round) is represented as a single-cell
  decrease symbol positioned at one stitch index, with the semantic
  implied by the symbol id (matches JIS convention).
- **Bulk upsert repository API.** See §4 tradeoff.
- **Rotational 90°/270° symmetry ops.** See §7 first bullet.
- **Mirror-pair editor** (author a new mirror pair for a custom symbol).
  Catalog is curated; custom-symbol authoring is Phase 36+ work.

## Consequences

### Positive

- Polar users are unblocked on both authoring and progress tracking. The
  "Phase 35" notice in the viewer goes away.
- Lace authors save ~4× tap work for four-way-symmetric panels.
- Row-level progress marking makes the progress loop match the
  stitch-by-stitch knitting rhythm for users who don't want to tap every
  stitch.
- Layer ops + grid resize close the "editor is a toy" gap that product
  discovery would otherwise surface.
- Mirror-pair lookup at the symbol-catalog layer means future symbol
  additions (including custom symbols in Phase 36) inherit mirror
  semantics through data, not code.

### Negative

- `PolarCellLayout` adds a second coord-transform code path. Unit tests
  must cover both rect and polar for any hit-test or render assertion
  touching cells. Expect ~30 new commonTest cases for geometry.
- `SymmetrizeLayersUseCase` introduces a new class of error surfacing
  (unmirrorable cells) that the editor UI must handle. Test matrix grows.
- The agent team explicitly rejected a bulk upsert API; Phase 37
  collaboration will have to re-open that design when diff semantics
  require it. Documented here so the Phase 37 author knows the path was
  considered and deferred, not missed.
- Symbol-catalog mirror-pair authoring is manual (knitter + implementer
  paired work). Expect ~20–40 mirror pairs to populate for MVP; more as
  the catalog grows.
- Polar inscribed-square glyph sizing means inner rings render smaller
  than outer. Acceptable for MVP (it's what commercial charts do); a
  Phase 35.x polish task can compensate with a minimum-size floor +
  glyph-thinning.

### Neutral

- `ChartExtents.Polar` data type unchanged.
- `ProjectSegment` schema unchanged from ADR-010.
- `contentHash` computation unchanged — symmetry ops produce new
  `layers` lists and the hash recomputes naturally.
- `EditHistory` semantics unchanged — symmetry and resize are one
  snapshot each; layer ops are one snapshot per op; undo/redo behaviour
  matches Phase 32 expectations.

## Considered alternatives

| Alternative | Pros | Cons | Why not chosen |
|---|---|---|---|
| Polar angle origin: 3 o'clock CCW | Matches US mathematical convention | Mismatches JIS round-chart convention — every Japanese amigurumi book reads 12 o'clock CW | Knitter vetoed; JP market is the primary user base |
| Polar glyph rendering: distort path to wedge shape | Uses full cell area | Glyphs become illegible at inner rings; no commercial chart does this | Inscribed-square matches JIS convention and renders cleanly |
| Symmetry op: mutate source layer in place | Simpler UX | Loses ability to keep original half as reference; forces immediate commit | Return-new-list preserves optionality and reuses `EditHistory.LayersSnapshot` trivially |
| Symmetry op: silently geo-flip asymmetric symbols | No UI surface for unmirrorable | Produces craft-incoherent charts that look fine to the eye but read wrong — users don't discover the bug until they're mid-project | Knitter vetoed; explicit prompt with skip option is craft-correct |
| Mirror variant: `selfSymmetric: Boolean` flag instead of `mirrorHorizontal: String?` = null | Explicit intent | Duplicates the null check with no new information; every glyph now carries two fields | Rejected; single nullable field is equivalent |
| Batch row-done: new `upsertBatch(segments)` repository API | One PendingSync row per batch; one Realtime event per batch | Requires bulk upsert in Supabase path; opaque to Phase 37 diff; partial-apply failure semantics | Deferred to Phase 37 when diff semantics force the question; loop is ≤1ms local |
| Batch row-done affordance: toolbar button | Discoverable | Toolbar is scarce; existing long-press cycle-tap affordance already suggests "long-press = extended op" | Long-press on row label extends the existing gesture vocabulary |
| Snap grid toggle | Familiar from other editors | The chart IS the grid; no off-grid state to snap to | Non-goal |
| Grid-size picker: separate dialog per axis | Simpler per-screen | Users resize both dimensions in one operation most of the time | One dialog with both inputs matches the mental model |
| Rotational 90°/270° symmetry | Editor feature parity with vector tools | Craft-incoherent for most symbols (knit bar, cable slant); would produce invalid JIS charts | Rejected; craft coherence > editor parity |
| Per-ring variable stitch count in MVP | Data-model complete | Requires a dedicated per-ring editor UI that doesn't exist | Ship uniform-ring in MVP; imported charts with variable rings still render correctly; authoring variable rings is Phase 35.x |

## References

- ADR-007: Pivot to chart authoring (framed Phase 35 as the advanced-editor home)
- ADR-008: Structured chart data model (`ChartExtents.Polar`, `contentHash` invariant, coordinate convention)
- ADR-009: Parametric symbols (mirror semantics for parametric symbols are out of scope here)
- ADR-010: Per-segment progress (Phase 34 polar deferral is what §2 closes)
- `docs/en/chart-coordinates.md` (y-up rect convention extended to polar here)
- Phase 32 Completed notes (editor MVP invariants, `EditHistory` scope)
- Phase 34 Completed notes (segment overlay, iOS gesture-conflict pattern, polar deferral notice)
- JIS L 0201 Table 2 + §5–§6 (round-chart angular convention, crochet symbol family)

## Phase 35.2f addendum — Layer ops MVP implementation decisions

§5 above reserved the scope (add / remove / rename / reorder / visibility /
lock + `EditHistory` entries + right-side drawer panel). Phase 35.2f
implements that scope; five implementation-detail decisions fell out of the
agent-team pass and are recorded here rather than a new ADR because they
resolve §5 rather than introduce new architecture.

1. **Lock blocks placement, symmetry write-target, and segment-progress tap.**
   Locked layers are read-only for (a) `PlaceCell` in the editor,
   (b) `ApplyRotationalSymmetry` / `ApplyReflection` when the target layer
   is locked, and (c) `ToggleSegmentState` / `MarkSegmentDone` in the viewer
   — viewer taps on a cell that belongs to a locked layer are silently
   dropped (the overlay still paints so the user can read prior progress).
   Lock does NOT block `Undo` / `Redo` — history is authoritative and
   each snapshot carries lock state itself, so undoing across a lock
   toggle restores the prior lock value. Lock does NOT block visibility
   toggle, rename, or delete — those are layer-list operations that
   intentionally let the user manage metadata on locked layers.

2. **Explicit `selectedLayerId` replaces the Phase 32 `layers[0]` hardcode.**
   `ChartEditorState.selectedLayerId: String?` is the single source of
   truth for placement target. Initial state points at the default
   `"L1"` layer (unchanged observable behavior). Load reassigns to
   `chart.layers.firstOrNull()?.id`. `AddLayer` auto-selects the new
   layer. `RemoveLayer` re-selects the nearest sibling (prior layer, or
   next layer if prior doesn't exist, or null if the list becomes empty).
   A null `selectedLayerId` is a valid draft state — `PlaceCell` is a
   no-op in that case rather than auto-creating a layer. Auto-create
   only happens once, in the initial state. `SelectLayer` is blocked
   while `pendingParameterInput` is set to prevent a parametric commit
   from landing on a layer other than the one the dialog was opened
   against.

3. **Reorder gesture: long-press-and-drag on a dedicated handle, not full-row.**
   Full-row long-press conflicts with tap-to-select. Reorder handle is the
   standard Material 3 `DragHandle` icon on Compose; SwiftUI uses
   `List.onMove` with `.editMode` toggled on drawer open. Reorder writes
   one `EditHistory` entry (the full layer-list snapshot before the move).

4. **Layer-list panel: right-side semi-modal drawer, toolbar-icon triggered.**
   Both platforms: tap layers-icon in overflow menu → drawer slides in
   from right. Compose: `ModalNavigationDrawer` with `drawerState` +
   right-edge anchor, `gesturesEnabled=false` (swipe-from-right conflicts
   with Android predictive-back). SwiftUI: `.sheet(isPresented:)` with
   `.presentationDetents([.medium, .large])` — native idiom matches iOS
   user expectation. Row content: drag handle · visibility eye · lock
   padlock · name (tap-to-rename inline via swapped `OutlinedTextField`
   / `TextField`) · overflow menu (delete). Delete confirmation dialog
   appears only when the layer has cells (empty layers delete silently).

5. **No schema bump.** `ChartLayer.visible` and `ChartLayer.locked` are
   already in the schema v2 data class with default values; Phase 32
   shipped them dormant. Phase 35.2f surfaces them in the editor UI and
   wires them through placement + viewer-tap enforcement. No ADR-008
   schema v3 bump is needed. `contentHash` depends on `layers`
   serialization so any layer-ops edit naturally recomputes the hash —
   matches `§5` "drawing identity changes" wording.

### Phase 35.2f explicitly out of scope

- **Symmetry-op lock respect in code path** — the `!locked` filter helper
  lands in 35.2f (consumed by placement); Phase 35.2g symmetry-op layer
  targeting consumes the same helper once symmetry ops gain a configurable
  target layer (Phase 32.2b hardcodes target to `layers[0]` regardless of
  selection; Phase 35.2g unifies symmetry-op target with `selectedLayerId`).
- **Custom layer color/tint** — Phase 36+ discovery-polish work.
- **Layer merge** — collaboration-era; Phase 37+.
- **Per-layer opacity slider** — Phase 36+ polish; visibility is boolean for
  MVP.
