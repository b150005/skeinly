package io.github.b150005.knitnote.ui.chart

import app.cash.turbine.test
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.CoordinateSystem
import io.github.b150005.knitnote.domain.model.StorageVariant
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.usecase.FakeStructuredChartRepository
import io.github.b150005.knitnote.domain.usecase.ObserveStructuredChartUseCase
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChartViewerViewModelTest {
    private val now = Instant.parse("2026-04-18T00:00:00Z")
    private lateinit var repo: FakeStructuredChartRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo = FakeStructuredChartRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun chart(
        patternId: String,
        layers: List<ChartLayer>,
    ): StructuredChart =
        StructuredChart(
            id = "chart-$patternId",
            patternId = patternId,
            ownerId = "user-1",
            schemaVersion = StructuredChart.CURRENT_SCHEMA_VERSION,
            storageVariant = StorageVariant.INLINE,
            coordinateSystem = CoordinateSystem.RECT_GRID,
            extents = ChartExtents.Rect(minX = 0, maxX = 0, minY = 0, maxY = 0),
            layers = layers,
            revisionId = "rev-0",
            parentRevisionId = null,
            contentHash = "h",
            createdAt = now,
            updatedAt = now,
        )

    @Test
    fun `state emits chart when repository observes a value`() =
        runTest {
            val seeded = chart("pat-1", listOf(ChartLayer(id = "L1", name = "Main")))
            repo.seed(seeded)
            val viewModel = ChartViewerViewModel("pat-1", ObserveStructuredChartUseCase(repo))

            viewModel.state.test {
                // Initial state — loading or already populated, depending on dispatcher race.
                val first = awaitItem()
                if (first.chart == null) {
                    val second = awaitItem()
                    assertEquals(seeded, second.chart)
                    assertFalse(second.isLoading)
                } else {
                    assertEquals(seeded, first.chart)
                    assertFalse(first.isLoading)
                }
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `state stays empty for pattern without a chart`() =
        runTest {
            val viewModel = ChartViewerViewModel("missing", ObserveStructuredChartUseCase(repo))

            viewModel.state.test {
                val first = awaitItem()
                if (first.isLoading) {
                    val second = awaitItem()
                    assertNull(second.chart)
                    assertFalse(second.isLoading)
                } else {
                    assertNull(first.chart)
                    assertFalse(first.isLoading)
                }
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `state surfaces error message when observe flow throws`() =
        runTest {
            val failingRepo =
                object : io.github.b150005.knitnote.domain.repository.StructuredChartRepository {
                    override suspend fun getByPatternId(patternId: String) = null

                    override fun observeByPatternId(patternId: String) =
                        kotlinx.coroutines.flow.flow<StructuredChart?> {
                            throw IllegalStateException("boom")
                        }

                    override suspend fun existsByPatternId(patternId: String) = false

                    override suspend fun create(chart: StructuredChart) = chart

                    override suspend fun update(chart: StructuredChart) = chart

                    override suspend fun delete(id: String) {}
                }
            val viewModel = ChartViewerViewModel("pat-1", ObserveStructuredChartUseCase(failingRepo))

            viewModel.state.test {
                var seenError = awaitItem()
                while (seenError.errorMessage == null) {
                    seenError = awaitItem()
                }
                assertEquals("boom", seenError.errorMessage)
                assertFalse(seenError.isLoading)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `toggleLayer adds and removes layer id from hidden set`() =
        runTest {
            val viewModel = ChartViewerViewModel("pat-1", ObserveStructuredChartUseCase(repo))

            assertTrue(
                viewModel.state.value.hiddenLayerIds
                    .isEmpty(),
            )

            viewModel.onEvent(ChartViewerEvent.ToggleLayer("L1"))
            assertEquals(setOf("L1"), viewModel.state.value.hiddenLayerIds)

            viewModel.onEvent(ChartViewerEvent.ToggleLayer("L2"))
            assertEquals(setOf("L1", "L2"), viewModel.state.value.hiddenLayerIds)

            viewModel.onEvent(ChartViewerEvent.ToggleLayer("L1"))
            assertEquals(setOf("L2"), viewModel.state.value.hiddenLayerIds)
        }
}
