# Spec — Structured Chart Editor

> **Purpose**: a stable, feature-organized view of the chart editor as it exists in main today. Use this when planning extensions; for *why* a decision was made, follow the ADR + archive references at the bottom.
>
> **Audience**: an agent loading this file as context before extending the editor surface. Optimized for skim, not narrative.
>
> **Scope**: the structured chart authoring surface — palette + canvas + history + save flow, both Compose (Android) and SwiftUI (iOS). Out of scope: chart **viewing** (ChartViewer), **diff**, **branch**, **PR conflict resolution**.

## Current shape

### File map

**Shared module — `commonMain`**

| Path | Role |
|---|---|
| [shared/.../ui/chart/ChartEditorViewModel.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/ui/chart/ChartEditorViewModel.kt) | ViewModel, `ChartEditorState`, `ChartEditorEvent` sealed interface, helpers (`trimCellsToExtents`, `trimRemovalCount`, `reconcileSelectedLayer`, `nextLayerNumber`, `selectedTargetLayer`) |
| [shared/.../ui/chart/ChartEditorScreen.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/ui/chart/ChartEditorScreen.kt) | Compose Multiplatform screen (~1500 LOC). Private composables: `EditorBody`, `EditorCanvas` (rect), `PolarEditorCanvas`, `LayerDrawerContent`, `LayerRow`, `ParameterInputDialog`, `PolarExtentsDialog`, `ResizeChartDialog`, `ChartMetadataMenu` |
| [shared/.../ui/chart/EditHistory.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/ui/chart/EditHistory.kt) | Bounded snapshot ring buffer (default cap 50). `record(snapshot) / undo() / redo()`. `Snapshot(extents, layers)` |
| [shared/.../ui/chart/SymbolPaletteStrip.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/ui/chart/SymbolPaletteStrip.kt) | Shared Compose palette: `FilterChip` category row + `LazyRow` symbol tiles + eraser cell. testTag `paletteSymbol_<id>` |
| [shared/.../ui/platform/SystemBackHandler.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/ui/platform/SystemBackHandler.kt) | `expect fun SystemBackHandler(enabled, onBack)`. iOS actual is no-op |
| [shared/.../domain/model/StructuredChart.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/model/StructuredChart.kt) | `StructuredChart`, `ChartExtents` (sealed: `Rect` / `Polar`), `ChartLayer`, `ChartCell`, `CraftType`, `ReadingConvention`, `CoordinateSystem`, `StorageVariant`. Companion `computeContentHash()` |
| [shared/.../domain/symbol/SymbolDefinition.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/symbol/SymbolDefinition.kt) | Symbol shape: `id`, `pathData`, `parameterSlots`, `fill`, `widthUnits`, `heightUnits` |
| [shared/.../domain/symbol/SymbolCatalog.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/symbol/SymbolCatalog.kt) | Interface: `get(id)`, `listByCategory(c)`, `all()` |
| [shared/.../domain/symbol/catalog/DefaultSymbolCatalog.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/symbol/catalog/DefaultSymbolCatalog.kt) | Compile-time catalog singleton; JIS bundle |
| [shared/.../domain/usecase/GetStructuredChartByPatternIdUseCase.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/usecase/GetStructuredChartByPatternIdUseCase.kt) | Loads chart by `patternId`. Returns `UseCaseResult<StructuredChart?>` (null = no chart yet) |
| [shared/.../domain/usecase/CreateStructuredChartUseCase.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/usecase/CreateStructuredChartUseCase.kt) | Creates first revision; mints `revisionId`, `parentRevisionId = null` |
| [shared/.../domain/usecase/UpdateStructuredChartUseCase.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/usecase/UpdateStructuredChartUseCase.kt) | Appends new revision; mints `revisionId = Uuid.random()`, sets `parentRevisionId = current.revisionId` |
| [shared/.../domain/chart/GridHitTest.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/chart/GridHitTest.kt) | `hitTest(rect)` and `hitTestPolar(polar)`. Screen-space tap → `(x, y)` cell coord |
| [shared/.../domain/chart/PolarSymmetry.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/chart/PolarSymmetry.kt) | Pure ops: `rotateCells(cells, polar, fold)`, `reflectCells(cells, polar, axisStitch)` |

**iOS — `iosApp/`**

| Path | Role |
|---|---|
| [iosApp/iosApp/Screens/StructuredChartEditorScreen.swift](../../../iosApp/iosApp/Screens/StructuredChartEditorScreen.swift) | SwiftUI mirror. `EditorCanvasView`, `SymbolPaletteView`, `ReflectionAxisPickBanner`, layer sheet, resize sheet, polar picker, parameter sheet, discard confirm |
| [iosApp/iosApp/Core/Bridging/ViewModelFactory.swift](../../../iosApp/iosApp/Core/Bridging/ViewModelFactory.swift) | `chartEditorViewModel(patternId:)` Koin resolution |
| [shared/.../iosMain/.../KoinHelper.kt](../../../shared/src/iosMain/kotlin/io/github/b150005/skeinly/KoinHelper.kt) | `wrapChartEditorState`, `wrapChartEditorSavedFlow` Swift bridge |

**Android — `androidApp/`**

No editor-specific files. The Compose screen ships from the shared module via `koinViewModel { parametersOf(patternId) }` in the NavGraph.

**Tests — `commonTest`**

[shared/.../commonTest/.../ui/chart/ChartEditorViewModelTest.kt](../../../shared/src/commonTest/kotlin/io/github/b150005/skeinly/ui/chart/ChartEditorViewModelTest.kt) — ~60 cases covering load / place / erase / undo / redo / save (create + update) / parametric input / layer ops / polar symmetry / resize / analytics events.

### State model

`ChartEditorState` (data class on the ViewModel). Field-by-field:

| Field | Type | Semantic |
|---|---|---|
| **Canvas / grid** | | |
| `draftExtents` | `ChartExtents` (sealed: `Rect` or `Polar`) | Canvas geometry. `Rect(minX, maxX, minY, maxY)` or `Polar(rings, stitchesPerRing: List<Int>)` |
| `draftLayers` | `List<ChartLayer>` | Ordered layer stack. Each holds `id`, `name`, `visible`, `locked`, `cells: List<ChartCell>` |
| `selectedLayerId` | `String?` | Active layer for placement / symmetry writes. Null when all layers removed (PlaceCell no-ops) |
| **Palette** | | |
| `selectedSymbolId` | `String?` | Null = eraser; non-null = symbol id to stamp |
| `selectedCategory` | `SymbolCategory` | Active JIS category tab |
| `paletteSymbols` | `List<SymbolDefinition>` | Live list for current category, from `SymbolCatalog.listByCategory()` |
| `draftCraftType` | `CraftType` | `KNIT` or `CROCHET`. Drives default palette + persisted at save |
| `draftReadingConvention` | `ReadingConvention` | `KNIT_FLAT` / `CROCHET_FLAT` / `ROUND`. Persisted; renderer does not yet act on it |
| **History** | | |
| `canUndo` / `canRedo` | `Boolean` | Mirror of `EditHistory.canUndo / canRedo` |
| `hasUnsavedChanges` | `Boolean` | True when draft differs from `original`, or when any cell exists on a never-saved chart. Drives discard guard + `SystemBackHandler` |
| **Save / error** | | |
| `original` | `StructuredChart?` | Null = new chart never saved; non-null = currently persisted version |
| `isSaving` | `Boolean` | Re-entrant saves rejected while true |
| `errorMessage` | `ErrorMessage?` | Cleared by `ClearError` event |
| `isLoading` | `Boolean` | True from construction until `GetStructuredChartByPatternIdUseCase` returns |
| `patternId` | `String` | Bound at construction |
| **Other** | | |
| `pendingParameterInput` | `PendingParameterInput?` | Non-null while a parametric symbol dialog is open. Blocks `PlaceCell` / `Undo` / `Redo` / `SelectLayer` until Confirm/Cancel |
| `isPickingReflectionAxis` | `Boolean` | Polar-only; next canvas tap is rerouted to `ApplyReflection(axisStitch = x)` instead of placing |

The `EditHistory` instance is **private to the ViewModel**, not in `ChartEditorState`. Bounded deque of `Snapshot(extents, layers)` with capacity 50.

### Domain entry points

**Load** — `GetStructuredChartByPatternIdUseCase(patternId)` from `init { viewModelScope.launch { load() } }`. Returns `UseCaseResult<StructuredChart?>`; null success means new-chart flow. Local-only (SQLDelight cache); no remote fetch.

**Save (create)** — `CreateStructuredChartUseCase(patternId, coordinateSystem, extents, layers, craftType, readingConvention)`. Mints `id` + `revisionId`, sets `parentRevisionId = null`. `contentHash = StructuredChart.computeContentHash(...)` — deterministic `h1-<hex8>` over JSON of extents + layers. `ownerId = AuthRepository.getCurrentUserId() ?: LocalUser.ID` (offline fallback). Returns `OperationNotAllowed` if a chart already exists for `patternId`.

**Save (update)** — `UpdateStructuredChartUseCase(current, extents, layers, craftType, readingConvention)`. Mints fresh `revisionId = Uuid.random()`, threads `parentRevisionId = current.revisionId`. Recomputes `contentHash`. Short-circuits (returns `current` unchanged) when extents + layers + metadata + `schemaVersion` are byte-identical.

**Palette** — `SymbolCatalog` injected at construction; sync. `listByCategory(category)` on `SelectCategory` event. `get(symbolId)` during `PlaceCell` to detect parametric symbols (non-null `parameterSlots` triggers the `PendingParameterInput` dialog).

### Invariants (load-bearing — DO NOT BREAK)

1. **Polar vs rect divergence**:
   - `ChartExtents` sealed: `Rect(minX, maxX, minY, maxY)` uses `(x, y)`; `Polar(rings, stitchesPerRing: List<Int>)` uses `(stitch, ring)`.
   - `CoordinateSystem` is set at create-time and **never** changes for an existing chart. `SetExtents` is silently rejected when `original != null`.
   - `GridHitTest.hitTest` and `hitTestPolar` are separate code paths. Never mix.
   - `PolarSymmetry.rotateCells` / `reflectCells` are no-ops on rect extents — handlers cast to `Polar` and return early on cast fail.
   - `trimCellsToExtents` uses different footprint logic per variant: rect cells check full `width × height`; polar cells check anchor only (`(x, y)` stitch/ring) per ADR-011 §7.

2. **Append-only `chart_revisions`** (ADR-013 §1):
   - `UpdateStructuredChartUseCase` never overwrites the prior `revisionId`. Always mints new + threads old as `parentRevisionId`.
   - `chart_revisions` has no DELETE policy. The editor never calls a delete path.

3. **`hasUnsavedChanges` is the single source of truth** for the discard guard. `computeUnsavedChanges(original, draftExtents, draftLayers, draftCraftType, draftReadingConvention)`:
   - New chart (`original == null`): true only when any layer has at least one cell.
   - Existing chart: compares all four fields against `original`.
   - Metadata changes alone (craft / reading) on a never-saved chart do **not** flip the flag.
   - Wired to `SystemBackHandler(enabled = state.hasUnsavedChanges || isPickingReflectionAxis || drawerState.isOpen)` in [ChartEditorScreen.kt:199](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/ui/chart/ChartEditorScreen.kt:199).

4. **`PendingParameterInput` is a modal block**: while non-null, `PlaceCell` / `Undo` / `Redo` / `SelectLayer` events are dropped. Confirm/Cancel are the only exits.

5. **E2E load-bearing testTags** — these are referenced from [e2e/flows/android/P1_chart_editor.yaml](../../../e2e/flows/android/P1_chart_editor.yaml). Renaming silently breaks the Maestro flow:
   - `openChartEditorLink` (ProjectDetail entry)
   - `editorCanvas` (canvas tap target)
   - `paletteSymbol_jis.knit.bobble` (non-parametric stamp test)
   - `editorSaveButton`
   - `openChartViewerLink` (post-save verification)
   - Internal-only (not in current E2E but reserved): `editorUndoButton`, `editorRedoButton`, `layersButton`, `editorOverflowButton`, `layerRow_<id>`, `addLayerFab`, `parameterInput_<key>`, `parameterConfirmButton`, `resizeChartConfirmButton`, `symmetryFold_<n>`, `symmetryReflect`, `axisPickBanner`.

6. **Compose vs SwiftUI divergences**:
   - Compose layer panel: `ModalNavigationDrawer` (right-edge via flipped `LayoutDirection`).
   - iOS layer panel: `.sheet(isPresented:)`. Per ADR-011 §5 addendum decision 4.
   - Compose has separate `EditorCanvas` (rect) + `PolarEditorCanvas` branches selected via `when (state.draftExtents)` in `EditorBody`. iOS `EditorCanvasView` handles both in one struct.

7. **Local-only load**: `GetStructuredChartByPatternIdUseCase` reads SQLDelight cache only. Cold-launch offline shows empty chart if the cache was not seeded.

## Extension points

### Adding a new tool / interaction mode

- Add a variant to `ChartEditorEvent` sealed interface (e.g. `data object SelectEraserTool`).
- Handle in `ChartEditorViewModel.onEvent()` `when` block.
- "Selected tool" is currently implicit: `selectedSymbolId == null` ⇒ eraser, non-null ⇒ stamp. A future named tool enum would join `selectedSymbolId` in `ChartEditorState`.

### Adding a new symbol family (catalog extension)

- New `SymbolCategory` entry in [SymbolCategory.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/symbol/SymbolCategory.kt).
- Add definitions to [DefaultSymbolCatalog.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/symbol/catalog/DefaultSymbolCatalog.kt).
- All rendering goes through `SymbolDrawing.kt` (SVG path interpreter). No per-symbol drawing code in the editor.
- ADR-008 governs id naming (`<namespace>.<category>.<name>`), `fill` flag, JIS reference conventions.
- ADR-009 governs parametric `parameterSlots` shape and dialog flow.
- ADR-011 §3 deferred: `SymbolDefinition.mirrorHorizontal` lookup for asymmetric glyphs under radial reflection. Today the `symbolId` passes through `PolarSymmetry.reflectCells` unchanged.

### Polar editing surface (Phase 35.2+)

Already wired:
- `ApplyRotationalSymmetry` + `ApplyReflection` events.
- `PolarEditorCanvas` composable.
- `GridHitTest.hitTestPolar` for screen-space mapping.
- `PolarCellLayout` for ring/stitch geometry.

Remaining 35.2+ work: `SymbolDefinition.mirrorHorizontal` catalog lookup, per-ring stitch-count heterogeneity UI, polar zoom.

### Adding analytics on a new event

- Inject `analyticsTracker: AnalyticsTracker?` (already in ctor; null-safe).
- Emit `analyticsTracker?.track(AnalyticsEvent.X)` from the relevant `onEvent` branch.
- For `ClickAction` events, register the new `ClickActionId` in [shared/.../data/analytics/ClickActionId.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/data/analytics/ClickActionId.kt) and the `Screen.ChartEditor` mapping is already in [Screen.kt](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/data/analytics/Screen.kt).

## Deferred / known limitations

| Item | Pointer |
|---|---|
| **Zoom** — no `rememberTransformableState` in the editor. WCAG 2.5.8 risk on 16×16+ grids. Estimated ~150 LOC Compose + ~100 LOC SwiftUI. | CLAUDE.md → Tech Debt Backlog → "Accessibility deferrals" |
| **Symmetry copy across layers** — rotational + radial reflection exist; copy-stamp across layers not started. `mirrorHorizontal` glyph flip deferred. | ADR-011 §3 |
| **Snap grid** — none; cells snap to integer coords by construction only. | Phase 35 advanced editor scope |
| **Grid size picker (new chart)** — hardcoded `DEFAULT_GRID_SIZE = 8` (rect). Polar uses the extents dialog. ResizeChart (Phase 35.3) covers existing charts. | [ChartEditorViewModel.kt:41](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/ui/chart/ChartEditorViewModel.kt:41) |
| **Renderer ignores `ReadingConvention`** — persisted + round-tripped, but row-numbering direction not yet honored. | [StructuredChart.kt:55](../../../shared/src/commonMain/kotlin/io/github/b150005/skeinly/domain/model/StructuredChart.kt:55) |
| **iOS NavigationStack edge-swipe** — left-edge swipe pops without discard guard. UIKit-level fix only. | CLAUDE.md → CI Known Limitations |
| **Local-only load** — no remote fetch on cold launch. Empty cache ⇒ empty chart. | Repository local-first contract |

## ADR + archive references

**ADRs governing the editor**:

| ADR | File | Scope |
|---|---|---|
| ADR-007 | [docs/en/adr/007-pivot-to-chart-authoring.md](../../../docs/en/adr/007-pivot-to-chart-authoring.md) | Pivot from v1 store submission to structured chart authoring |
| ADR-008 | [docs/en/adr/008-structured-chart-data-model.md](../../../docs/en/adr/008-structured-chart-data-model.md) | Symbol id scheme, document envelope, content hash, fill-vs-stroke |
| ADR-009 | [docs/en/adr/009-parametric-symbols.md](../../../docs/en/adr/009-parametric-symbols.md) | `parameterSlots`, deferred-commit dialog, `symbolParameters` on cell |
| ADR-011 | [docs/en/adr/011-phase-35-advanced-editor.md](../../../docs/en/adr/011-phase-35-advanced-editor.md) | Multi-layer ops, polar authoring, resize, layer lock/visibility |
| ADR-013 | [docs/en/adr/013-phase-37-collaboration-core.md](../../../docs/en/adr/013-phase-37-collaboration-core.md) | Append-only `chart_revisions`, parent chain, content hash |

**Phase archive entries** in [docs/en/phase/completed-archive.md](../../../docs/en/phase/completed-archive.md):

- Phase 32.x — editor MVP + parametric input + craft picker + system-back interception
- Phase 33.x — i18n consumer wiring + testTag de-brittling
- Phase 35.1a–d — polar coord support, multi-layer ops, resize
- Phase 37.x — collaboration core (the revision chain the editor appends to)

**Maintenance rule**: when a Phase commit changes the chart-editor surface, update this spec in the same commit (per CLAUDE.md `## Development Workflow` step 7).
