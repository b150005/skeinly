package io.github.b150005.knitnote.ui.chart

import app.cash.turbine.test
import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.model.ChartCell
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.CoordinateSystem
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
    fun `selectSymbol updates selectedSymbolId only, does not touch history`() =
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
}
