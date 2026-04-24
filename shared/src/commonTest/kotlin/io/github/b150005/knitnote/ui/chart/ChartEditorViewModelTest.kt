package io.github.b150005.knitnote.ui.chart

import app.cash.turbine.test
import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.model.ChartCell
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.CoordinateSystem
import io.github.b150005.knitnote.domain.model.CraftType
import io.github.b150005.knitnote.domain.model.ReadingConvention
import io.github.b150005.knitnote.domain.model.StorageVariant
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.symbol.SymbolCategory
import io.github.b150005.knitnote.domain.symbol.catalog.DefaultSymbolCatalog
import io.github.b150005.knitnote.domain.usecase.CreateStructuredChartUseCase
import io.github.b150005.knitnote.domain.usecase.FakeAuthRepository
import io.github.b150005.knitnote.domain.usecase.FakeStructuredChartRepository
import io.github.b150005.knitnote.domain.usecase.GetStructuredChartByPatternIdUseCase
import io.github.b150005.knitnote.domain.usecase.UpdateStructuredChartUseCase
import io.github.b150005.knitnote.testJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChartEditorViewModelTest {
    private val now = Instant.parse("2026-04-20T00:00:00Z")
    private lateinit var repo: FakeStructuredChartRepository
    private lateinit var auth: FakeAuthRepository
    private val catalog = DefaultSymbolCatalog.INSTANCE

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo = FakeStructuredChartRepository()
        auth = FakeAuthRepository()
        auth.setAuthState(AuthState.Authenticated(userId = "user-1", email = "t@example.com"))
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun seededChart(
        patternId: String = "pat-1",
        layers: List<ChartLayer> = listOf(ChartLayer(id = "L1", name = "Main")),
        craftType: CraftType = CraftType.KNIT,
        readingConvention: ReadingConvention = ReadingConvention.KNIT_FLAT,
    ): StructuredChart =
        StructuredChart(
            id = "chart-seed",
            patternId = patternId,
            ownerId = "user-1",
            schemaVersion = StructuredChart.CURRENT_SCHEMA_VERSION,
            storageVariant = StorageVariant.INLINE,
            coordinateSystem = CoordinateSystem.RECT_GRID,
            extents = ChartExtents.Rect(minX = 0, maxX = 7, minY = 0, maxY = 7),
            layers = layers,
            revisionId = "rev-0",
            parentRevisionId = null,
            contentHash =
                StructuredChart.computeContentHash(
                    ChartExtents.Rect(minX = 0, maxX = 7, minY = 0, maxY = 7),
                    layers,
                    testJson,
                ),
            createdAt = now,
            updatedAt = now,
            craftType = craftType,
            readingConvention = readingConvention,
        )

    private fun newViewModel(patternId: String = "pat-1"): ChartEditorViewModel =
        ChartEditorViewModel(
            patternId = patternId,
            getStructuredChart = GetStructuredChartByPatternIdUseCase(repo),
            createStructuredChart = CreateStructuredChartUseCase(repo, auth, testJson),
            updateStructuredChart = UpdateStructuredChartUseCase(repo, testJson),
            symbolCatalog = catalog,
        )

    private suspend fun awaitReady(viewModel: ChartEditorViewModel): ChartEditorState {
        // UnconfinedTestDispatcher drives the init block eagerly, but give coroutines
        // a chance to drain in case any dispatcher hops remain.
        var state = viewModel.state.value
        while (state.isLoading) {
            kotlinx.coroutines.yield()
            state = viewModel.state.value
        }
        return state
    }

    // 1.
    @Test
    fun `load emits Loading then Ready with original and draft equal`() =
        runTest {
            val seeded = seededChart(layers = listOf(ChartLayer(id = "L1", name = "Main")))
            repo.seed(seeded)

            val viewModel = newViewModel()
            val ready = awaitReady(viewModel)

            assertFalse(ready.isLoading)
            assertEquals(seeded, ready.original)
            assertEquals(seeded.extents, ready.draftExtents)
            assertEquals(seeded.layers, ready.draftLayers)
            assertFalse(ready.hasUnsavedChanges)
        }

    // 2.
    @Test
    fun `load with no existing chart emits Ready with original null and empty draft`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            val ready = awaitReady(viewModel)

            assertFalse(ready.isLoading)
            assertNull(ready.original)
            assertTrue(ready.draftExtents is ChartExtents.Rect)
            assertEquals(1, ready.draftLayers.size)
            assertTrue(ready.draftLayers[0].cells.isEmpty())
        }

    // 3.
    @Test
    fun `selectSymbol updates selectedSymbolId only without touching history`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)

            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))

            val state = viewModel.state.value
            assertEquals("jis.knit.k", state.selectedSymbolId)
            assertFalse(state.canUndo)
            assertFalse(state.canRedo)
            assertFalse(state.hasUnsavedChanges)
        }

    // 4.
    @Test
    fun `placeCell on empty cell adds ChartCell and pushes history`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))

            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 2, y = 3))

            val state = viewModel.state.value
            val cells = state.draftLayers[0].cells
            assertEquals(1, cells.size)
            assertEquals(ChartCell(symbolId = "jis.knit.k", x = 2, y = 3), cells[0])
            assertTrue(state.canUndo)
            assertFalse(state.canRedo)
            assertTrue(state.hasUnsavedChanges)
        }

    // 5.
    @Test
    fun `placeCell on occupied cell overwrites and records one history entry`() =
        runTest {
            val seeded =
                seededChart(
                    layers =
                        listOf(
                            ChartLayer(
                                id = "L1",
                                name = "Main",
                                cells = listOf(ChartCell(symbolId = "jis.knit.k", x = 1, y = 1)),
                            ),
                        ),
                )
            repo.seed(seeded)

            val viewModel = newViewModel()
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.p"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 1, y = 1))

            val state = viewModel.state.value
            val cells = state.draftLayers[0].cells
            assertEquals(1, cells.size)
            assertEquals("jis.knit.p", cells[0].symbolId)
            assertTrue(state.canUndo)
            assertTrue(state.hasUnsavedChanges)
        }

    // 6.
    @Test
    fun `placeCell with selectedSymbolId null acts as eraser`() =
        runTest {
            val seeded =
                seededChart(
                    layers =
                        listOf(
                            ChartLayer(
                                id = "L1",
                                name = "Main",
                                cells = listOf(ChartCell(symbolId = "jis.knit.k", x = 1, y = 1)),
                            ),
                        ),
                )
            repo.seed(seeded)

            val viewModel = newViewModel()
            awaitReady(viewModel)
            // selectedSymbolId remains null (default) => eraser mode

            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 1, y = 1))
            val afterErase = viewModel.state.value
            assertTrue(afterErase.draftLayers[0].cells.isEmpty())
            assertTrue(afterErase.canUndo)

            // Erase on an empty cell is a no-op — no additional history entry
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 2, y = 2))
            val afterNoop = viewModel.state.value
            assertTrue(afterNoop.draftLayers[0].cells.isEmpty())
            // canUndo still true from the first erase; we cannot strictly prove that
            // history size is 1, but we can prove undo then undo is no-op
            viewModel.onEvent(ChartEditorEvent.Undo)
            val afterUndo = viewModel.state.value
            assertEquals(1, afterUndo.draftLayers[0].cells.size)
            assertFalse(afterUndo.canUndo)
        }

    // 7.
    @Test
    fun `undo restores previous draft and enables redo`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))

            viewModel.onEvent(ChartEditorEvent.Undo)

            val state = viewModel.state.value
            assertTrue(state.draftLayers[0].cells.isEmpty())
            assertFalse(state.canUndo)
            assertTrue(state.canRedo)
        }

    // 8.
    @Test
    fun `redo reapplies the undone edit`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))
            viewModel.onEvent(ChartEditorEvent.Undo)

            viewModel.onEvent(ChartEditorEvent.Redo)

            val state = viewModel.state.value
            assertEquals(1, state.draftLayers[0].cells.size)
            assertTrue(state.canUndo)
            assertFalse(state.canRedo)
        }

    // 9.
    @Test
    fun `undo on empty history is a no-op`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)

            val before = viewModel.state.value
            viewModel.onEvent(ChartEditorEvent.Undo)
            val after = viewModel.state.value

            assertEquals(before.draftLayers, after.draftLayers)
            assertFalse(after.canUndo)
            assertFalse(after.canRedo)
        }

    // 10.
    @Test
    fun `redo past tip is a no-op`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)

            viewModel.onEvent(ChartEditorEvent.Redo)
            val state = viewModel.state.value
            assertFalse(state.canRedo)
        }

    // 11. — important invariant
    @Test
    fun `placing after undo clears redo stack`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 1, y = 0))
            viewModel.onEvent(ChartEditorEvent.Undo) // back to one cell
            assertTrue(viewModel.state.value.canRedo)

            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 2, y = 0))

            val state = viewModel.state.value
            assertFalse(state.canRedo)
            assertEquals(2, state.draftLayers[0].cells.size)
        }

    // 12.
    @Test
    fun `51 edits evicts the oldest undo entry`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))

            // Make 51 distinct placements.
            repeat(51) { i ->
                viewModel.onEvent(ChartEditorEvent.PlaceCell(x = i % 8, y = i / 8))
            }

            // Undo 50 times; the 51st undo must be a no-op because the oldest was evicted.
            repeat(50) { viewModel.onEvent(ChartEditorEvent.Undo) }
            val afterAllUndos = viewModel.state.value
            // Eviction invariant: 51 edits start with an empty pre-edit state on the
            // undo stack; the first entry (empty draft) is dropped when the 51st
            // record overflows the 50-entry buffer. After 50 undos we must be one
            // step past-empty, i.e. at least one cell still present, with no further
            // undos available.
            assertTrue(afterAllUndos.draftLayers[0].cells.isNotEmpty(), "evicted entry should leave cells present")
            assertFalse(afterAllUndos.canUndo)

            viewModel.onEvent(ChartEditorEvent.Undo) // one more — should be a no-op
            val finalState = viewModel.state.value
            assertEquals(afterAllUndos.draftLayers, finalState.draftLayers)
            assertFalse(finalState.canUndo)
        }

    // 13.
    @Test
    fun `save with no original calls Create and emits saved event`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-new")
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))

            viewModel.saved.test {
                viewModel.onEvent(ChartEditorEvent.Save)
                awaitItem() // saved event
                cancelAndConsumeRemainingEvents()
            }

            val state = viewModel.state.value
            assertNotNull(state.original)
            assertEquals(state.draftLayers, state.original.layers)
            assertFalse(state.hasUnsavedChanges)
            assertFalse(state.isSaving)
            assertNull(state.errorMessage)
        }

    // 14.
    @Test
    fun `save with existing original calls Update and emits saved event`() =
        runTest {
            val seeded = seededChart()
            repo.seed(seeded)

            val viewModel = newViewModel()
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))

            viewModel.saved.test {
                viewModel.onEvent(ChartEditorEvent.Save)
                awaitItem()
                cancelAndConsumeRemainingEvents()
            }

            val state = viewModel.state.value
            assertNotNull(state.original)
            assertNotEquals(seeded.revisionId, state.original.revisionId)
            assertFalse(state.hasUnsavedChanges)
        }

    // 15.
    @Test
    fun `save when draft matches original short-circuits`() =
        runTest {
            val seeded = seededChart()
            repo.seed(seeded)

            val viewModel = newViewModel()
            awaitReady(viewModel)
            // No edits — draft equals original. Save should be a no-op (no saved event).

            viewModel.onEvent(ChartEditorEvent.Save)

            val state = viewModel.state.value
            assertFalse(state.isSaving)
            assertFalse(state.hasUnsavedChanges)
            assertEquals(seeded, state.original)
        }

    // 16.
    @Test
    fun `save failure surfaces error and keeps unsaved changes`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-new")
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))
            repo.failNext = IllegalStateException("disk full")

            viewModel.onEvent(ChartEditorEvent.Save)

            val state = viewModel.state.value
            assertFalse(state.isSaving)
            assertNull(state.original)
            assertTrue(state.hasUnsavedChanges)
            assertNotNull(state.errorMessage)
        }

    // 17.
    @Test
    fun `SelectCategory changes palette source but not draft`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)
            val beforeDraft = viewModel.state.value.draftLayers

            viewModel.onEvent(ChartEditorEvent.SelectCategory(SymbolCategory.CROCHET))

            val state = viewModel.state.value
            assertEquals(SymbolCategory.CROCHET, state.selectedCategory)
            assertEquals(beforeDraft, state.draftLayers)
            // palette symbols should reflect the new category
            assertTrue(state.paletteSymbols.isNotEmpty())
            assertTrue(state.paletteSymbols.all { it.category == SymbolCategory.CROCHET })
        }

    // --- Phase 32.2: craft / reading metadata picker ---

    // 18.
    @Test
    fun `load with existing chart seeds draft craft and reading from original`() =
        runTest {
            val seeded =
                seededChart(
                    craftType = CraftType.CROCHET,
                    readingConvention = ReadingConvention.ROUND,
                )
            repo.seed(seeded)

            val viewModel = newViewModel()
            val ready = awaitReady(viewModel)

            assertEquals(CraftType.CROCHET, ready.draftCraftType)
            assertEquals(ReadingConvention.ROUND, ready.draftReadingConvention)
            assertFalse(ready.hasUnsavedChanges)
        }

    // 19.
    @Test
    fun `load with no existing chart defaults craft to KNIT and reading to KNIT_FLAT`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            val ready = awaitReady(viewModel)

            assertEquals(CraftType.KNIT, ready.draftCraftType)
            assertEquals(ReadingConvention.KNIT_FLAT, ready.draftReadingConvention)
        }

    // 20.
    @Test
    fun `SelectCraft on existing chart updates draftCraftType and marks unsaved`() =
        runTest {
            val seeded = seededChart()
            repo.seed(seeded)
            val viewModel = newViewModel()
            awaitReady(viewModel)

            viewModel.onEvent(ChartEditorEvent.SelectCraft(CraftType.CROCHET))

            val state = viewModel.state.value
            assertEquals(CraftType.CROCHET, state.draftCraftType)
            assertTrue(state.hasUnsavedChanges)
        }

    // 21.
    @Test
    fun `SelectReading on existing chart updates draftReadingConvention and marks unsaved`() =
        runTest {
            val seeded = seededChart()
            repo.seed(seeded)
            val viewModel = newViewModel()
            awaitReady(viewModel)

            viewModel.onEvent(ChartEditorEvent.SelectReading(ReadingConvention.CROCHET_FLAT))

            val state = viewModel.state.value
            assertEquals(ReadingConvention.CROCHET_FLAT, state.draftReadingConvention)
            assertTrue(state.hasUnsavedChanges)
        }

    // 21b.
    @Test
    fun `SelectCraft alone on new chart does not mark dirty until a cell is placed`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)

            viewModel.onEvent(ChartEditorEvent.SelectCraft(CraftType.CROCHET))

            val state = viewModel.state.value
            assertEquals(CraftType.CROCHET, state.draftCraftType)
            // An empty new chart is not saveable — metadata alone does not make it dirty.
            assertFalse(state.hasUnsavedChanges)
        }

    // 21c.
    @Test
    fun `SelectReading alone on new chart does not mark dirty until a cell is placed`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)

            viewModel.onEvent(ChartEditorEvent.SelectReading(ReadingConvention.ROUND))

            val state = viewModel.state.value
            assertEquals(ReadingConvention.ROUND, state.draftReadingConvention)
            assertFalse(state.hasUnsavedChanges)
        }

    // 22.
    @Test
    fun `SelectCraft with same value is a no-op`() =
        runTest {
            val seeded = seededChart(craftType = CraftType.CROCHET)
            repo.seed(seeded)
            val viewModel = newViewModel()
            awaitReady(viewModel)

            viewModel.onEvent(ChartEditorEvent.SelectCraft(CraftType.CROCHET))

            val state = viewModel.state.value
            assertEquals(CraftType.CROCHET, state.draftCraftType)
            assertFalse(state.hasUnsavedChanges)
        }

    // 23.
    @Test
    fun `save with changed craft persists new craft on existing chart`() =
        runTest {
            val seeded = seededChart(craftType = CraftType.KNIT, readingConvention = ReadingConvention.KNIT_FLAT)
            repo.seed(seeded)

            val viewModel = newViewModel()
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectCraft(CraftType.CROCHET))
            viewModel.onEvent(ChartEditorEvent.SelectReading(ReadingConvention.ROUND))

            viewModel.saved.test {
                viewModel.onEvent(ChartEditorEvent.Save)
                awaitItem()
                cancelAndConsumeRemainingEvents()
            }

            val state = viewModel.state.value
            val original = state.original
            assertNotNull(original)
            assertEquals(CraftType.CROCHET, original.craftType)
            assertEquals(ReadingConvention.ROUND, original.readingConvention)
            assertFalse(state.hasUnsavedChanges)
        }

    // 24.
    @Test
    fun `save with new chart creates with selected craft and reading`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-new")
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectCraft(CraftType.CROCHET))
            viewModel.onEvent(ChartEditorEvent.SelectReading(ReadingConvention.CROCHET_FLAT))
            // a new chart requires at least one cell to be considered dirty
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.crochet.sc"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))

            viewModel.saved.test {
                viewModel.onEvent(ChartEditorEvent.Save)
                awaitItem()
                cancelAndConsumeRemainingEvents()
            }

            val state = viewModel.state.value
            val original = state.original
            assertNotNull(original)
            assertEquals(CraftType.CROCHET, original.craftType)
            assertEquals(ReadingConvention.CROCHET_FLAT, original.readingConvention)
        }

    // 25.
    @Test
    fun `metadata-only change still marks unsaved on existing chart`() =
        runTest {
            val seeded = seededChart()
            repo.seed(seeded)

            val viewModel = newViewModel()
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectCraft(CraftType.CROCHET))

            val state = viewModel.state.value
            assertTrue(state.hasUnsavedChanges)
            // Drawing payload unchanged
            assertEquals(seeded.extents, state.draftExtents)
            assertEquals(seeded.layers, state.draftLayers)
        }

    // --- Phase 32.3: parametric symbol input (ADR-009 §7) ---
    // Notes on K/N backtick restriction: no `(`, `)`, `,` in backticked fn names.

    // 26. Parametric placement opens dialog without committing.
    @Test
    fun `placeCell with parametric selection on empty cell opens pending input and does not commit`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.crochet.ch-space"))

            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 2, y = 3))

            val state = viewModel.state.value
            val pending = state.pendingParameterInput
            assertNotNull(pending, "parametric placement must open pending input")
            assertEquals("jis.crochet.ch-space", pending.symbolId)
            assertEquals(2, pending.x)
            assertEquals(3, pending.y)
            assertFalse(pending.isEditingExisting)
            assertTrue(pending.slots.isNotEmpty())
            assertTrue(pending.currentValues.isEmpty(), "new placement starts with no values")
            // Cell must NOT be committed yet.
            assertTrue(state.draftLayers[0].cells.isEmpty())
            assertFalse(state.canUndo)
            assertFalse(state.hasUnsavedChanges)
        }

    // 27. Confirm commits the cell with symbolParameters, pushes history, marks unsaved.
    @Test
    fun `ConfirmParameterInput commits cell with symbolParameters and pushes history`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.crochet.ch-space"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 1, y = 1))

            viewModel.onEvent(ChartEditorEvent.ConfirmParameterInput(mapOf("count" to "5")))

            val state = viewModel.state.value
            assertNull(state.pendingParameterInput)
            val cells = state.draftLayers[0].cells
            assertEquals(1, cells.size)
            assertEquals("jis.crochet.ch-space", cells[0].symbolId)
            assertEquals(mapOf("count" to "5"), cells[0].symbolParameters)
            assertTrue(state.canUndo)
            assertTrue(state.hasUnsavedChanges)
        }

    // 28. Cancel discards the pending input without committing or pushing history.
    @Test
    fun `CancelParameterInput clears pending input without committing a cell or pushing history`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.crochet.ch-space"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 1, y = 1))

            viewModel.onEvent(ChartEditorEvent.CancelParameterInput)

            val state = viewModel.state.value
            assertNull(state.pendingParameterInput)
            assertTrue(state.draftLayers[0].cells.isEmpty())
            assertFalse(state.canUndo)
            assertFalse(state.hasUnsavedChanges)
        }

    // 29. Re-edit: tapping an existing parametric cell with the same symbol selected
    // reopens the dialog prepopulated with the cell's current values.
    @Test
    fun `placeCell on existing parametric cell with same selection opens re-edit prepopulated`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)
            // First place a ch-space with count=3.
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.crochet.ch-space"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))
            viewModel.onEvent(ChartEditorEvent.ConfirmParameterInput(mapOf("count" to "3")))

            // Tap the same cell again.
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))

            val pending = viewModel.state.value.pendingParameterInput
            assertNotNull(pending, "tapping parametric cell should reopen dialog")
            assertTrue(pending.isEditingExisting)
            assertEquals("jis.crochet.ch-space", pending.symbolId)
            assertEquals(mapOf("count" to "3"), pending.currentValues)
        }

    // 30. Re-edit confirm replaces values in-place, no new cell.
    @Test
    fun `ConfirmParameterInput on re-edit replaces existing cell values without adding a new cell`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.crochet.ch-space"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))
            viewModel.onEvent(ChartEditorEvent.ConfirmParameterInput(mapOf("count" to "3")))
            // Re-open via second tap.
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))

            viewModel.onEvent(ChartEditorEvent.ConfirmParameterInput(mapOf("count" to "7")))

            val state = viewModel.state.value
            assertNull(state.pendingParameterInput)
            val cells = state.draftLayers[0].cells
            assertEquals(1, cells.size, "re-edit must not add a second cell")
            assertEquals(mapOf("count" to "7"), cells[0].symbolParameters)
        }

    // 31. Eraser (null selection) on a parametric cell erases immediately — no dialog.
    @Test
    fun `placeCell with null selection on parametric cell erases immediately without opening dialog`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.crochet.ch-space"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))
            viewModel.onEvent(ChartEditorEvent.ConfirmParameterInput(mapOf("count" to "5")))
            // Switch to eraser.
            viewModel.onEvent(ChartEditorEvent.SelectSymbol(null))

            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))

            val state = viewModel.state.value
            assertNull(state.pendingParameterInput, "eraser must not open dialog")
            assertTrue(state.draftLayers[0].cells.isEmpty())
        }

    // 32. Defensive: PlaceCell while pending input is set is ignored.
    @Test
    fun `placeCell while pendingParameterInput is set is ignored`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.crochet.ch-space"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 1, y = 1))
            val firstPending = viewModel.state.value.pendingParameterInput
            assertNotNull(firstPending)

            // Second tap at a different cell should be ignored while dialog is open.
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 5, y = 5))

            val state = viewModel.state.value
            // Pending stays pointed at the first tap.
            assertEquals(1, state.pendingParameterInput?.x)
            assertEquals(1, state.pendingParameterInput?.y)
            assertTrue(state.draftLayers[0].cells.isEmpty())
        }

    // 33. Overwrite parametric with non-parametric — immediate, no dialog.
    @Test
    fun `placeCell with non-parametric selection on parametric cell overwrites immediately`() =
        runTest {
            // Setup guard — the assertions below would pass vacuously if the catalog
            // lookup returned null (fall-through to non-parametric branch). Pin the
            // pre-condition explicitly.
            val knitK = catalog.get("jis.knit.k")
            assertNotNull(knitK, "catalog must expose jis.knit.k")
            assertTrue(knitK.parameterSlots.isEmpty(), "jis.knit.k must be non-parametric")

            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.crochet.ch-space"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))
            viewModel.onEvent(ChartEditorEvent.ConfirmParameterInput(mapOf("count" to "4")))
            // Switch to a non-parametric symbol.
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))

            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))

            val state = viewModel.state.value
            assertNull(state.pendingParameterInput)
            val cells = state.draftLayers[0].cells
            assertEquals(1, cells.size)
            assertEquals("jis.knit.k", cells[0].symbolId)
            assertTrue(cells[0].symbolParameters.isEmpty())
        }

    // --- Phase 35.2a: polar extents picker ---

    // 34. New-chart Flat → Polar switch updates draftExtents and leaves layers empty.
    @Test
    fun `SetExtents to Polar on new chart updates draftExtents and resets layers`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)

            val polar = ChartExtents.Polar(rings = 3, stitchesPerRing = listOf(8, 16, 24))
            viewModel.onEvent(ChartEditorEvent.SetExtents(polar))

            val state = viewModel.state.value
            assertEquals(polar, state.draftExtents)
            assertEquals(1, state.draftLayers.size)
            assertTrue(state.draftLayers[0].cells.isEmpty())
            assertTrue(state.canUndo)
            // Empty new chart → not dirty yet.
            assertFalse(state.hasUnsavedChanges)
        }

    // 35. New-chart switch after placing cells clears those cells.
    @Test
    fun `SetExtents resets cells placed under the prior coordinate system`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))
            assertTrue(
                viewModel.state.value.draftLayers[0]
                    .cells
                    .isNotEmpty(),
            )

            val polar = ChartExtents.Polar(rings = 2, stitchesPerRing = listOf(6, 12))
            viewModel.onEvent(ChartEditorEvent.SetExtents(polar))

            val state = viewModel.state.value
            assertEquals(polar, state.draftExtents)
            assertTrue(state.draftLayers[0].cells.isEmpty(), "cells must reset on extents switch")
        }

    // 36. Existing-chart SetExtents is rejected.
    @Test
    fun `SetExtents is ignored when an original chart is loaded`() =
        runTest {
            val seeded = seededChart()
            repo.seed(seeded)
            val viewModel = newViewModel()
            awaitReady(viewModel)

            val before = viewModel.state.value
            val polar = ChartExtents.Polar(rings = 3, stitchesPerRing = listOf(8, 16, 24))
            viewModel.onEvent(ChartEditorEvent.SetExtents(polar))

            val after = viewModel.state.value
            assertEquals(before.draftExtents, after.draftExtents)
            assertEquals(before.draftLayers, after.draftLayers)
            assertFalse(after.hasUnsavedChanges)
            assertFalse(after.canUndo)
        }

    // 37. Undo after SetExtents restores prior extents + layers.
    @Test
    fun `undo after SetExtents restores the prior rect extents and layers`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 2, y = 2))
            val priorExtents = viewModel.state.value.draftExtents
            val priorLayers = viewModel.state.value.draftLayers

            val polar = ChartExtents.Polar(rings = 3, stitchesPerRing = listOf(6, 12, 18))
            viewModel.onEvent(ChartEditorEvent.SetExtents(polar))
            viewModel.onEvent(ChartEditorEvent.Undo)

            val state = viewModel.state.value
            assertEquals(priorExtents, state.draftExtents)
            assertEquals(priorLayers, state.draftLayers)
        }

    // 38. SetExtents to the same value is a no-op.
    @Test
    fun `SetExtents to the same value does not push history`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)
            val currentRect = viewModel.state.value.draftExtents

            viewModel.onEvent(ChartEditorEvent.SetExtents(currentRect))

            val state = viewModel.state.value
            assertEquals(currentRect, state.draftExtents)
            assertFalse(state.canUndo)
        }

    // 39. Save with polar extents creates a chart with POLAR_ROUND coordinate system.
    @Test
    fun `save with polar extents creates chart with POLAR_ROUND coordinateSystem`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-polar")
            awaitReady(viewModel)
            val polar = ChartExtents.Polar(rings = 2, stitchesPerRing = listOf(6, 12))
            viewModel.onEvent(ChartEditorEvent.SetExtents(polar))
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            // polar cell: x = stitch, y = ring
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))

            viewModel.saved.test {
                viewModel.onEvent(ChartEditorEvent.Save)
                awaitItem()
                cancelAndConsumeRemainingEvents()
            }

            val state = viewModel.state.value
            val original = state.original
            assertNotNull(original)
            assertEquals(CoordinateSystem.POLAR_ROUND, original.coordinateSystem)
            assertEquals(polar, original.extents)
            assertFalse(state.hasUnsavedChanges)
        }

    // 40. Phase 35.2b: rotational symmetry replicates cells around the ring.
    @Test
    fun `ApplyRotationalSymmetry fold 4 replicates a polar cell to three more stitches`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-polar-sym")
            awaitReady(viewModel)
            val polar = ChartExtents.Polar(rings = 1, stitchesPerRing = listOf(8))
            viewModel.onEvent(ChartEditorEvent.SetExtents(polar))
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))

            viewModel.onEvent(ChartEditorEvent.ApplyRotationalSymmetry(fold = 4))

            val cells =
                viewModel.state.value.draftLayers[0]
                    .cells
            assertEquals(4, cells.size)
            assertEquals(setOf(0, 2, 4, 6), cells.map { it.x }.toSet())
        }

    // 41. Phase 35.2b: rotational symmetry on a rect chart is ignored.
    @Test
    fun `ApplyRotationalSymmetry on rect chart is a no-op`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-rect-sym")
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))
            val before =
                viewModel.state.value.draftLayers[0]
                    .cells

            viewModel.onEvent(ChartEditorEvent.ApplyRotationalSymmetry(fold = 4))

            val after =
                viewModel.state.value.draftLayers[0]
                    .cells
            assertEquals(before, after)
        }

    // 42. Phase 35.2b: undo reverses a rotational symmetry op.
    @Test
    fun `undo restores cells placed before ApplyRotationalSymmetry`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-polar-sym-undo")
            awaitReady(viewModel)
            val polar = ChartExtents.Polar(rings = 1, stitchesPerRing = listOf(8))
            viewModel.onEvent(ChartEditorEvent.SetExtents(polar))
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))
            val preSymmetry =
                viewModel.state.value.draftLayers[0]
                    .cells

            viewModel.onEvent(ChartEditorEvent.ApplyRotationalSymmetry(fold = 2))
            viewModel.onEvent(ChartEditorEvent.Undo)

            val restored =
                viewModel.state.value.draftLayers[0]
                    .cells
            assertEquals(preSymmetry, restored)
            assertTrue(viewModel.state.value.canRedo)
        }

    // 43. Phase 35.2b: reflection mirrors cells across a stitch-index axis.
    @Test
    fun `ApplyReflection mirrors polar cell across axis 0`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-polar-refl")
            awaitReady(viewModel)
            val polar = ChartExtents.Polar(rings = 1, stitchesPerRing = listOf(8))
            viewModel.onEvent(ChartEditorEvent.SetExtents(polar))
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 1, y = 0))

            viewModel.onEvent(ChartEditorEvent.ApplyReflection(axisStitch = 0))

            val cells =
                viewModel.state.value.draftLayers[0]
                    .cells
            assertEquals(2, cells.size)
            assertEquals(setOf(1, 7), cells.map { it.x }.toSet())
        }

    // 44. Phase 35.2b: symmetry ops ignored while a parametric dialog is open.
    @Test
    fun `ApplyRotationalSymmetry is ignored while pendingParameterInput is set`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-polar-sym-pending")
            awaitReady(viewModel)
            val polar = ChartExtents.Polar(rings = 1, stitchesPerRing = listOf(8))
            viewModel.onEvent(ChartEditorEvent.SetExtents(polar))
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))
            // Open a parametric dialog by selecting a parametric symbol and tapping.
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.crochet.ch-space"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 2, y = 0))
            assertNotNull(viewModel.state.value.pendingParameterInput)
            val before =
                viewModel.state.value.draftLayers[0]
                    .cells

            viewModel.onEvent(ChartEditorEvent.ApplyRotationalSymmetry(fold = 4))

            val after =
                viewModel.state.value.draftLayers[0]
                    .cells
            assertEquals(before, after)
        }

    // 45. Phase 35.2c: StartPickReflectionAxis sets the flag on a polar chart.
    @Test
    fun `StartPickReflectionAxis sets isPickingReflectionAxis on polar chart`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-axis-pick")
            awaitReady(viewModel)
            val polar = ChartExtents.Polar(rings = 1, stitchesPerRing = listOf(8))
            viewModel.onEvent(ChartEditorEvent.SetExtents(polar))

            viewModel.onEvent(ChartEditorEvent.StartPickReflectionAxis)

            assertTrue(viewModel.state.value.isPickingReflectionAxis)
        }

    // 46. Phase 35.2c: StartPickReflectionAxis on a rect chart is a silent no-op.
    @Test
    fun `StartPickReflectionAxis is a no-op on rect chart`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-axis-pick-rect")
            awaitReady(viewModel)

            viewModel.onEvent(ChartEditorEvent.StartPickReflectionAxis)

            assertFalse(viewModel.state.value.isPickingReflectionAxis)
        }

    // 47. Phase 35.2c: tap on canvas while picking reroutes to ApplyReflection with tapped x.
    @Test
    fun `PlaceCell while picking reroutes to ApplyReflection at tapped stitch`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-axis-pick-reroute")
            awaitReady(viewModel)
            val polar = ChartExtents.Polar(rings = 1, stitchesPerRing = listOf(8))
            viewModel.onEvent(ChartEditorEvent.SetExtents(polar))
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            // Seed one cell at stitch 1 so reflection has something to mirror.
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 1, y = 0))
            viewModel.onEvent(ChartEditorEvent.StartPickReflectionAxis)
            assertTrue(viewModel.state.value.isPickingReflectionAxis)

            // Tap stitch 2 — should reroute to ApplyReflection(axisStitch=2) and clear flag.
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 2, y = 0))

            val state = viewModel.state.value
            assertFalse(state.isPickingReflectionAxis)
            val cells = state.draftLayers[0].cells
            // 2 * 2 - 1 = 3 → mirrored stitch. Original stays, new one at x=3.
            assertEquals(setOf(1, 3), cells.map { it.x }.toSet())
        }

    // 48. Phase 35.2c: CancelPickReflectionAxis exits picking mode without mutating cells.
    @Test
    fun `CancelPickReflectionAxis clears flag and leaves cells untouched`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-axis-pick-cancel")
            awaitReady(viewModel)
            val polar = ChartExtents.Polar(rings = 1, stitchesPerRing = listOf(8))
            viewModel.onEvent(ChartEditorEvent.SetExtents(polar))
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 1, y = 0))
            viewModel.onEvent(ChartEditorEvent.StartPickReflectionAxis)
            val before =
                viewModel.state.value.draftLayers[0]
                    .cells

            viewModel.onEvent(ChartEditorEvent.CancelPickReflectionAxis)

            val after = viewModel.state.value
            assertFalse(after.isPickingReflectionAxis)
            assertEquals(before, after.draftLayers[0].cells)
        }

    // 49. Phase 35.2c: direct ApplyReflection dispatch still works and clears an active pick.
    @Test
    fun `ApplyReflection clears picking flag as a defensive cleanup`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-axis-direct-refl")
            awaitReady(viewModel)
            val polar = ChartExtents.Polar(rings = 1, stitchesPerRing = listOf(8))
            viewModel.onEvent(ChartEditorEvent.SetExtents(polar))
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 1, y = 0))
            viewModel.onEvent(ChartEditorEvent.StartPickReflectionAxis)

            viewModel.onEvent(ChartEditorEvent.ApplyReflection(axisStitch = 0))

            assertFalse(viewModel.state.value.isPickingReflectionAxis)
        }

    // 50. Phase 35.2c: SetExtents polar→rect clears an in-flight axis pick.
    @Test
    fun `SetExtents clears isPickingReflectionAxis on coordinate switch`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-axis-extents-switch")
            awaitReady(viewModel)
            val polar = ChartExtents.Polar(rings = 1, stitchesPerRing = listOf(8))
            viewModel.onEvent(ChartEditorEvent.SetExtents(polar))
            viewModel.onEvent(ChartEditorEvent.StartPickReflectionAxis)
            assertTrue(viewModel.state.value.isPickingReflectionAxis)

            viewModel.onEvent(
                ChartEditorEvent.SetExtents(ChartExtents.Rect(minX = 0, maxX = 7, minY = 0, maxY = 7)),
            )

            assertFalse(viewModel.state.value.isPickingReflectionAxis)
        }

    // 51a. Phase 35.2c: undo while picking clears the banner/ring state.
    @Test
    fun `undo while picking clears isPickingReflectionAxis`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-axis-undo")
            awaitReady(viewModel)
            val polar = ChartExtents.Polar(rings = 1, stitchesPerRing = listOf(8))
            viewModel.onEvent(ChartEditorEvent.SetExtents(polar))
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 1, y = 0))
            viewModel.onEvent(ChartEditorEvent.StartPickReflectionAxis)
            assertTrue(viewModel.state.value.isPickingReflectionAxis)

            viewModel.onEvent(ChartEditorEvent.Undo)

            assertFalse(viewModel.state.value.isPickingReflectionAxis)
        }

    // 51b. Phase 35.2c: metadata changes cancel the pick per the state KDoc contract.
    @Test
    fun `selectCraft while picking clears isPickingReflectionAxis`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-axis-craft")
            awaitReady(viewModel)
            val polar = ChartExtents.Polar(rings = 1, stitchesPerRing = listOf(8))
            viewModel.onEvent(ChartEditorEvent.SetExtents(polar))
            viewModel.onEvent(ChartEditorEvent.StartPickReflectionAxis)
            assertTrue(viewModel.state.value.isPickingReflectionAxis)

            viewModel.onEvent(ChartEditorEvent.SelectCraft(CraftType.CROCHET))

            assertFalse(viewModel.state.value.isPickingReflectionAxis)
        }

    // 51. Phase 35.2c: axis-picking mode blocks placeCell from actually placing/erasing cells.
    @Test
    fun `placeCell while picking does not add or remove cells on the layer`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-axis-no-place")
            awaitReady(viewModel)
            val polar = ChartExtents.Polar(rings = 1, stitchesPerRing = listOf(8))
            viewModel.onEvent(ChartEditorEvent.SetExtents(polar))
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))
            viewModel.onEvent(ChartEditorEvent.StartPickReflectionAxis)

            // Tap stitch 0 — axis=0 reflection on a cell at stitch 0 is self-mirror,
            // so cells must NOT grow; the picking path must short-circuit placement.
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))

            val cells =
                viewModel.state.value.draftLayers[0]
                    .cells
            // Exactly the seed cell remains — no second placement, no erase.
            assertEquals(1, cells.size)
            assertEquals(0, cells[0].x)
            // And picking flag is cleared even on the geometric no-op.
            assertFalse(viewModel.state.value.isPickingReflectionAxis)
        }

    // 52. Phase 35.2f: initial selectedLayerId matches the default L1 layer.
    @Test
    fun `initial state selects the default L1 layer for fresh charts`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            val ready = awaitReady(viewModel)
            assertEquals("L1", ready.selectedLayerId)
        }

    // 53. Phase 35.2f: load reassigns selectedLayerId to the first layer of the chart.
    @Test
    fun `load pins selectedLayerId to the first layer of the loaded chart`() =
        runTest {
            val seeded =
                seededChart(
                    layers =
                        listOf(
                            ChartLayer(id = "LA", name = "Alpha"),
                            ChartLayer(id = "LB", name = "Beta"),
                        ),
                )
            repo.seed(seeded)
            val viewModel = newViewModel()
            val ready = awaitReady(viewModel)
            assertEquals("LA", ready.selectedLayerId)
        }

    // 54. Phase 35.2f: SelectLayer updates selectedLayerId.
    @Test
    fun `SelectLayer event updates selectedLayerId when the id exists`() =
        runTest {
            val seeded =
                seededChart(
                    layers =
                        listOf(
                            ChartLayer(id = "LA", name = "Alpha"),
                            ChartLayer(id = "LB", name = "Beta"),
                        ),
                )
            repo.seed(seeded)
            val viewModel = newViewModel()
            awaitReady(viewModel)

            viewModel.onEvent(ChartEditorEvent.SelectLayer("LB"))

            assertEquals("LB", viewModel.state.value.selectedLayerId)
            assertFalse(viewModel.state.value.hasUnsavedChanges)
        }

    // 55. Phase 35.2f: SelectLayer with an unknown id is silently ignored.
    @Test
    fun `SelectLayer with unknown id is a silent no-op`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectLayer("Lnope"))
            assertEquals("L1", viewModel.state.value.selectedLayerId)
        }

    // 56. Phase 35.2f: placeCell routes to selectedLayerId and leaves siblings alone.
    @Test
    fun `placeCell routes cells to the selected layer not the first layer`() =
        runTest {
            val seeded =
                seededChart(
                    layers =
                        listOf(
                            ChartLayer(id = "LA", name = "Alpha"),
                            ChartLayer(id = "LB", name = "Beta"),
                        ),
                )
            repo.seed(seeded)
            val viewModel = newViewModel()
            awaitReady(viewModel)

            viewModel.onEvent(ChartEditorEvent.SelectLayer("LB"))
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 4, y = 4))

            val layers = viewModel.state.value.draftLayers
            assertEquals(2, layers.size)
            assertTrue(layers.first { it.id == "LA" }.cells.isEmpty())
            val targetCells = layers.first { it.id == "LB" }.cells
            assertEquals(1, targetCells.size)
            assertEquals(ChartCell(symbolId = "jis.knit.k", x = 4, y = 4), targetCells[0])
        }

    // 57. Phase 35.2f: placeCell on a locked target layer is a silent no-op.
    @Test
    fun `placeCell on a locked target layer leaves state untouched`() =
        runTest {
            val seeded =
                seededChart(
                    layers = listOf(ChartLayer(id = "L1", name = "Main", locked = true)),
                )
            repo.seed(seeded)
            val viewModel = newViewModel()
            awaitReady(viewModel)

            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 2, y = 2))

            val state = viewModel.state.value
            assertTrue(state.draftLayers[0].cells.isEmpty())
            assertFalse(state.canUndo)
            assertFalse(state.hasUnsavedChanges)
        }

    // 58. Phase 35.2f: AddLayer appends with auto id + name and auto-selects.
    @Test
    fun `AddLayer appends a new layer and auto-selects it`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)

            viewModel.onEvent(ChartEditorEvent.AddLayer)

            val state = viewModel.state.value
            assertEquals(2, state.draftLayers.size)
            assertEquals("L1", state.draftLayers[0].id)
            assertEquals("L2", state.draftLayers[1].id)
            assertEquals("Layer 2", state.draftLayers[1].name)
            assertEquals("L2", state.selectedLayerId)
            assertTrue(state.canUndo)
        }

    // 59. Phase 35.2f: RemoveLayer deletes + re-selects the prior sibling.
    @Test
    fun `RemoveLayer deletes the target and re-selects the prior sibling`() =
        runTest {
            val seeded =
                seededChart(
                    layers =
                        listOf(
                            ChartLayer(id = "LA", name = "Alpha"),
                            ChartLayer(id = "LB", name = "Beta"),
                            ChartLayer(id = "LC", name = "Gamma"),
                        ),
                )
            repo.seed(seeded)
            val viewModel = newViewModel()
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectLayer("LB"))

            viewModel.onEvent(ChartEditorEvent.RemoveLayer("LB"))

            val state = viewModel.state.value
            assertEquals(2, state.draftLayers.size)
            assertEquals(listOf("LA", "LC"), state.draftLayers.map { it.id })
            assertEquals("LA", state.selectedLayerId)
            assertTrue(state.canUndo)
            assertTrue(state.hasUnsavedChanges)
        }

    // 60. Phase 35.2f: RemoveLayer of the only layer leaves selection null.
    @Test
    fun `RemoveLayer of the only layer results in null selectedLayerId`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)

            viewModel.onEvent(ChartEditorEvent.RemoveLayer("L1"))

            val state = viewModel.state.value
            assertTrue(state.draftLayers.isEmpty())
            assertNull(state.selectedLayerId)

            // Subsequent placement is a silent no-op with nothing selected.
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))
            assertTrue(
                viewModel.state.value.draftLayers
                    .isEmpty(),
            )
        }

    // 61. Phase 35.2f: RenameLayer changes name with trimming + records history.
    @Test
    fun `RenameLayer updates the name when non-blank and records a history entry`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)

            viewModel.onEvent(ChartEditorEvent.RenameLayer(layerId = "L1", newName = "  Pattern A  "))

            val state = viewModel.state.value
            assertEquals("Pattern A", state.draftLayers[0].name)
            assertTrue(state.canUndo)
        }

    // 62. Phase 35.2f: ReorderLayer moves a layer and records history.
    @Test
    fun `ReorderLayer moves a layer from one index to another`() =
        runTest {
            val seeded =
                seededChart(
                    layers =
                        listOf(
                            ChartLayer(id = "LA", name = "Alpha"),
                            ChartLayer(id = "LB", name = "Beta"),
                            ChartLayer(id = "LC", name = "Gamma"),
                        ),
                )
            repo.seed(seeded)
            val viewModel = newViewModel()
            awaitReady(viewModel)

            viewModel.onEvent(ChartEditorEvent.ReorderLayer(fromIndex = 0, toIndex = 2))

            val state = viewModel.state.value
            assertEquals(listOf("LB", "LC", "LA"), state.draftLayers.map { it.id })
            assertTrue(state.canUndo)
        }

    // 63. Phase 35.2f: ToggleLayerVisibility flips visible and marks dirty.
    @Test
    fun `ToggleLayerVisibility flips the visible flag and records history`() =
        runTest {
            val seeded = seededChart(layers = listOf(ChartLayer(id = "L1", name = "Main")))
            repo.seed(seeded)
            val viewModel = newViewModel()
            awaitReady(viewModel)
            assertTrue(
                viewModel.state.value.draftLayers[0]
                    .visible,
            )

            viewModel.onEvent(ChartEditorEvent.ToggleLayerVisibility("L1"))

            val state = viewModel.state.value
            assertFalse(state.draftLayers[0].visible)
            assertTrue(state.hasUnsavedChanges)
            assertTrue(state.canUndo)
        }

    // 64. Phase 35.2f: ToggleLayerLock flips locked + later placement is no-op.
    @Test
    fun `ToggleLayerLock flips the locked flag and blocks subsequent placement`() =
        runTest {
            val viewModel = newViewModel(patternId = "pat-missing")
            awaitReady(viewModel)

            viewModel.onEvent(ChartEditorEvent.ToggleLayerLock("L1"))
            assertTrue(
                viewModel.state.value.draftLayers[0]
                    .locked,
            )

            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.knit.k"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 0, y = 0))
            assertTrue(
                viewModel.state.value.draftLayers[0]
                    .cells
                    .isEmpty(),
            )
        }

    // 65. Phase 35.2f: SelectLayer is blocked while a parametric dialog is open.
    @Test
    fun `SelectLayer is ignored while pendingParameterInput is set`() =
        runTest {
            val seeded =
                seededChart(
                    layers =
                        listOf(
                            ChartLayer(id = "LA", name = "Alpha"),
                            ChartLayer(id = "LB", name = "Beta"),
                        ),
                )
            repo.seed(seeded)
            val viewModel = newViewModel()
            awaitReady(viewModel)

            // Open a parametric dialog on the current selection.
            viewModel.onEvent(ChartEditorEvent.SelectSymbol("jis.crochet.ch-space"))
            viewModel.onEvent(ChartEditorEvent.PlaceCell(x = 1, y = 1))
            assertNotNull(viewModel.state.value.pendingParameterInput)

            // SelectLayer while pending must not retarget the dialog.
            viewModel.onEvent(ChartEditorEvent.SelectLayer("LB"))
            assertEquals("LA", viewModel.state.value.selectedLayerId)
        }

    // 66. Phase 35.2f: undo after RemoveLayer restores the layer + re-selects it.
    @Test
    fun `Undo reconciles selectedLayerId when the removed layer is restored`() =
        runTest {
            val seeded =
                seededChart(
                    layers =
                        listOf(
                            ChartLayer(id = "LA", name = "Alpha"),
                            ChartLayer(id = "LB", name = "Beta"),
                        ),
                )
            repo.seed(seeded)
            val viewModel = newViewModel()
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.SelectLayer("LB"))
            viewModel.onEvent(ChartEditorEvent.RemoveLayer("LB"))
            assertEquals("LA", viewModel.state.value.selectedLayerId)

            viewModel.onEvent(ChartEditorEvent.Undo)

            val state = viewModel.state.value
            assertEquals(2, state.draftLayers.size)
            // Selection may be LA (preserved during RemoveLayer) or LB
            // (reconciled to the restored list's first entry if selection was
            // cleared). Either is consistent with reconcileSelectedLayer's
            // contract: keep the current selection if it's present in the
            // restored list. LA is still in the restored list, so LA wins.
            assertEquals("LA", state.selectedLayerId)
            assertNotEquals(null, state.draftLayers.firstOrNull { it.id == "LB" })
        }

    // 67. Phase 35.2f: undo reconciles selection via fall-back when the
    // selected layer no longer exists in the restored list. Exercises the
    // `restoredLayers.firstOrNull()?.id` branch of reconcileSelectedLayer
    // that test 66 does not reach.
    @Test
    fun `Undo falls back to first restored layer when selection is not in the restored list`() =
        runTest {
            val seeded =
                seededChart(
                    layers =
                        listOf(
                            ChartLayer(id = "L1", name = "Main"),
                            ChartLayer(id = "L2", name = "Cables"),
                        ),
                )
            repo.seed(seeded)
            val viewModel = newViewModel()
            awaitReady(viewModel)

            // AddLayer auto-selects the new layer; undo must restore the
            // pre-add list and reconcile selection since L3 is gone.
            viewModel.onEvent(ChartEditorEvent.AddLayer)
            assertEquals("L3", viewModel.state.value.selectedLayerId)

            viewModel.onEvent(ChartEditorEvent.Undo)

            val state = viewModel.state.value
            assertEquals(listOf("L1", "L2"), state.draftLayers.map { it.id })
            // Fall-back path: L3 is not in restored list, so selection
            // clamps to the first restored layer.
            assertEquals("L1", state.selectedLayerId)
        }

    // 68. Phase 35.2f: redo path also reconciles. Parallel to test 67 but
    // via the redo stack.
    @Test
    fun `Redo reconciles selectedLayerId against the forward-restored layers`() =
        runTest {
            val seeded =
                seededChart(
                    layers = listOf(ChartLayer(id = "L1", name = "Main")),
                )
            repo.seed(seeded)
            val viewModel = newViewModel()
            awaitReady(viewModel)
            viewModel.onEvent(ChartEditorEvent.AddLayer)
            assertEquals("L2", viewModel.state.value.selectedLayerId)
            viewModel.onEvent(ChartEditorEvent.Undo)
            assertEquals("L1", viewModel.state.value.selectedLayerId)

            viewModel.onEvent(ChartEditorEvent.Redo)

            val state = viewModel.state.value
            // Redo re-applies the add. Selection at redo time was "L1" which
            // exists in the forward-restored ["L1", "L2"] list, so selection
            // stays on "L1" via the keep-existing branch.
            assertEquals(listOf("L1", "L2"), state.draftLayers.map { it.id })
            assertEquals("L1", state.selectedLayerId)
        }
}
