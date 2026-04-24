package io.github.b150005.knitnote.ui.chart

import app.cash.turbine.test
import io.github.b150005.knitnote.domain.model.ChartCell
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.CoordinateSystem
import io.github.b150005.knitnote.domain.model.ProjectSegment
import io.github.b150005.knitnote.domain.model.SegmentState
import io.github.b150005.knitnote.domain.model.StorageVariant
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.usecase.FakeProjectSegmentRepository
import io.github.b150005.knitnote.domain.usecase.FakeStructuredChartRepository
import io.github.b150005.knitnote.domain.usecase.GetStructuredChartByPatternIdUseCase
import io.github.b150005.knitnote.domain.usecase.MarkRowSegmentsDoneUseCase
import io.github.b150005.knitnote.domain.usecase.MarkSegmentDoneUseCase
import io.github.b150005.knitnote.domain.usecase.ObserveProjectSegmentsUseCase
import io.github.b150005.knitnote.domain.usecase.ObserveStructuredChartUseCase
import io.github.b150005.knitnote.domain.usecase.ToggleSegmentStateUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
    private lateinit var segmentRepo: FakeProjectSegmentRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo = FakeStructuredChartRepository()
        segmentRepo = FakeProjectSegmentRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun chart(
        patternId: String,
        layers: List<ChartLayer>,
        coordinateSystem: CoordinateSystem = CoordinateSystem.RECT_GRID,
    ): StructuredChart =
        StructuredChart(
            id = "chart-$patternId",
            patternId = patternId,
            ownerId = "user-1",
            schemaVersion = StructuredChart.CURRENT_SCHEMA_VERSION,
            storageVariant = StorageVariant.INLINE,
            coordinateSystem = coordinateSystem,
            extents = ChartExtents.Rect(minX = 0, maxX = 2, minY = 0, maxY = 2),
            layers = layers,
            revisionId = "rev-0",
            parentRevisionId = null,
            contentHash = "h",
            createdAt = now,
            updatedAt = now,
        )

    private fun makeViewModel(
        patternId: String,
        projectId: String? = null,
    ): ChartViewerViewModel =
        ChartViewerViewModel(
            patternId = patternId,
            projectId = projectId,
            observeStructuredChart = ObserveStructuredChartUseCase(repo),
            observeProjectSegments = ObserveProjectSegmentsUseCase(segmentRepo),
            toggleSegmentState = ToggleSegmentStateUseCase(segmentRepo, authRepository = null),
            markSegmentDone = MarkSegmentDoneUseCase(segmentRepo, authRepository = null),
            markRowSegmentsDone =
                MarkRowSegmentsDoneUseCase(
                    repository = segmentRepo,
                    getStructuredChart = GetStructuredChartByPatternIdUseCase(repo),
                    authRepository = null,
                ),
        )

    @Test
    fun `state emits chart when repository observes a value`() =
        runTest {
            val seeded = chart("pat-1", listOf(ChartLayer(id = "L1", name = "Main")))
            repo.seed(seeded)
            val viewModel = makeViewModel("pat-1")

            viewModel.state.test {
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
            val viewModel = makeViewModel("missing")

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
            val viewModel =
                ChartViewerViewModel(
                    patternId = "pat-1",
                    projectId = null,
                    observeStructuredChart = ObserveStructuredChartUseCase(failingRepo),
                    observeProjectSegments = ObserveProjectSegmentsUseCase(segmentRepo),
                    toggleSegmentState = ToggleSegmentStateUseCase(segmentRepo, authRepository = null),
                    markSegmentDone = MarkSegmentDoneUseCase(segmentRepo, authRepository = null),
                    markRowSegmentsDone =
                        MarkRowSegmentsDoneUseCase(
                            repository = segmentRepo,
                            getStructuredChart = GetStructuredChartByPatternIdUseCase(failingRepo),
                            authRepository = null,
                        ),
                )

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
            val viewModel = makeViewModel("pat-1")

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

    @Test
    fun `segments flow populates overlay map keyed by layerId and cell coords`() =
        runTest {
            repo.seed(chart("pat-1", listOf(ChartLayer(id = "L1", name = "Main"))))
            segmentRepo.seed(
                ProjectSegment(
                    id = ProjectSegment.buildId("proj-1", "L1", 2, 3),
                    projectId = "proj-1",
                    layerId = "L1",
                    cellX = 2,
                    cellY = 3,
                    state = SegmentState.WIP,
                    updatedAt = now,
                ),
            )
            val viewModel = makeViewModel("pat-1", projectId = "proj-1")

            advanceUntilIdle()

            val map = viewModel.state.value.segments
            assertEquals(1, map.size)
            assertEquals(SegmentState.WIP, map[SegmentKey("L1", 2, 3)])
        }

    @Test
    fun `tapCell on todo inserts a wip segment via use case`() =
        runTest {
            repo.seed(chart("pat-1", listOf(ChartLayer(id = "L1", name = "Main"))))
            val viewModel = makeViewModel("pat-1", projectId = "proj-1")

            viewModel.onEvent(ChartViewerEvent.TapCell("L1", 1, 2))
            advanceUntilIdle()

            val stored = segmentRepo.getById(ProjectSegment.buildId("proj-1", "L1", 1, 2))
            assertEquals(SegmentState.WIP, stored?.state)
            assertEquals(SegmentState.WIP, viewModel.state.value.segments[SegmentKey("L1", 1, 2)])
        }

    @Test
    fun `tapCell is a no-op when projectId is null`() =
        runTest {
            repo.seed(chart("pat-1", listOf(ChartLayer(id = "L1", name = "Main"))))
            val viewModel = makeViewModel("pat-1", projectId = null)

            viewModel.onEvent(ChartViewerEvent.TapCell("L1", 1, 2))
            advanceUntilIdle()

            assertNull(segmentRepo.getById(ProjectSegment.buildId("pat-1", "L1", 1, 2)))
            assertTrue(
                viewModel.state.value.segments
                    .isEmpty(),
            )
        }

    @Test
    fun `tapCell is a no-op when the layer is hidden`() =
        runTest {
            repo.seed(
                chart(
                    "pat-1",
                    listOf(
                        ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("knit", 1, 2))),
                    ),
                ),
            )
            val viewModel = makeViewModel("pat-1", projectId = "proj-1")

            viewModel.onEvent(ChartViewerEvent.ToggleLayer("L1"))
            viewModel.onEvent(ChartViewerEvent.TapCell("L1", 1, 2))
            advanceUntilIdle()

            assertNull(segmentRepo.getById(ProjectSegment.buildId("proj-1", "L1", 1, 2)))
        }

    @Test
    fun `longPressCell forces segment to done regardless of prior state`() =
        runTest {
            repo.seed(chart("pat-1", listOf(ChartLayer(id = "L1", name = "Main"))))
            val viewModel = makeViewModel("pat-1", projectId = "proj-1")

            viewModel.onEvent(ChartViewerEvent.LongPressCell("L1", 4, 5))
            advanceUntilIdle()

            val stored = segmentRepo.getById(ProjectSegment.buildId("proj-1", "L1", 4, 5))
            assertEquals(SegmentState.DONE, stored?.state)
        }

    @Test
    fun `isPolar flag mirrors the chart coordinate system`() =
        runTest {
            repo.seed(
                chart(
                    patternId = "pat-1",
                    layers = listOf(ChartLayer(id = "L1", name = "Main")),
                    coordinateSystem = CoordinateSystem.POLAR_ROUND,
                ),
            )
            val viewModel = makeViewModel("pat-1", projectId = "proj-1")

            advanceUntilIdle()
            assertTrue(viewModel.state.value.isPolar)
        }

    @Test
    fun `tapCell on polar chart toggles segment state via use case`() =
        runTest {
            // Phase 35.1d regression anchor: prior to 35.1d the ViewModel dropped polar
            // tap events behind an isPolar guard; removing that guard is what makes the
            // polar overlay actually interactive end-to-end.
            repo.seed(
                chart(
                    patternId = "pat-1",
                    layers = listOf(ChartLayer(id = "L1", name = "Main")),
                    coordinateSystem = CoordinateSystem.POLAR_ROUND,
                ),
            )
            val viewModel = makeViewModel("pat-1", projectId = "proj-1")

            viewModel.onEvent(ChartViewerEvent.TapCell("L1", 0, 0))
            advanceUntilIdle()

            val stored = segmentRepo.getById(ProjectSegment.buildId("proj-1", "L1", 0, 0))
            assertEquals(SegmentState.WIP, stored?.state)
            assertEquals(SegmentState.WIP, viewModel.state.value.segments[SegmentKey("L1", 0, 0)])
        }

    @Test
    fun `longPressCell on polar chart marks segment done`() =
        runTest {
            repo.seed(
                chart(
                    patternId = "pat-1",
                    layers = listOf(ChartLayer(id = "L1", name = "Main")),
                    coordinateSystem = CoordinateSystem.POLAR_ROUND,
                ),
            )
            val viewModel = makeViewModel("pat-1", projectId = "proj-1")

            viewModel.onEvent(ChartViewerEvent.LongPressCell("L1", 2, 1))
            advanceUntilIdle()

            val stored = segmentRepo.getById(ProjectSegment.buildId("proj-1", "L1", 2, 1))
            assertEquals(SegmentState.DONE, stored?.state)
        }

    @Test
    fun `markRowDone flips every visible cell on the target row to done`() =
        runTest {
            repo.seed(
                chart(
                    "pat-1",
                    listOf(
                        ChartLayer(
                            id = "L1",
                            name = "Main",
                            cells =
                                listOf(
                                    ChartCell(symbolId = "jis.knit.k", x = 0, y = 2),
                                    ChartCell(symbolId = "jis.knit.k", x = 1, y = 2),
                                ),
                        ),
                    ),
                ),
            )
            val viewModel = makeViewModel("pat-1", projectId = "proj-1")

            viewModel.onEvent(ChartViewerEvent.MarkRowDone(row = 2))
            advanceUntilIdle()

            assertEquals(
                SegmentState.DONE,
                segmentRepo.getById(ProjectSegment.buildId("proj-1", "L1", 0, 2))?.state,
            )
            assertEquals(
                SegmentState.DONE,
                segmentRepo.getById(ProjectSegment.buildId("proj-1", "L1", 1, 2))?.state,
            )
        }

    @Test
    fun `markRowDone is a no-op when projectId is null`() =
        runTest {
            repo.seed(
                chart(
                    "pat-1",
                    listOf(
                        ChartLayer(
                            id = "L1",
                            name = "Main",
                            cells = listOf(ChartCell(symbolId = "jis.knit.k", x = 0, y = 2)),
                        ),
                    ),
                ),
            )
            val viewModel = makeViewModel("pat-1", projectId = null)

            viewModel.onEvent(ChartViewerEvent.MarkRowDone(row = 2))
            advanceUntilIdle()

            assertNull(segmentRepo.getById(ProjectSegment.buildId("proj-1", "L1", 0, 2)))
        }

    @Test
    fun `markRowDone skips layers toggled off via hiddenLayerIds`() =
        runTest {
            repo.seed(
                chart(
                    "pat-1",
                    listOf(
                        ChartLayer(
                            id = "L1",
                            name = "Main",
                            cells = listOf(ChartCell(symbolId = "jis.knit.k", x = 0, y = 3)),
                        ),
                        ChartLayer(
                            id = "L2",
                            name = "Reference",
                            cells = listOf(ChartCell(symbolId = "jis.knit.p", x = 1, y = 3)),
                        ),
                    ),
                ),
            )
            val viewModel = makeViewModel("pat-1", projectId = "proj-1")

            viewModel.onEvent(ChartViewerEvent.ToggleLayer("L2"))
            viewModel.onEvent(ChartViewerEvent.MarkRowDone(row = 3))
            advanceUntilIdle()

            assertEquals(
                SegmentState.DONE,
                segmentRepo.getById(ProjectSegment.buildId("proj-1", "L1", 0, 3))?.state,
            )
            // L2 was UI-hidden; the row-done dispatch must NOT have written its cells.
            assertNull(segmentRepo.getById(ProjectSegment.buildId("proj-1", "L2", 1, 3)))
        }

    @Test
    fun `markRowDone surfaces a failure from the use case as errorMessage`() =
        runTest {
            val failingSegmentRepo =
                object : io.github.b150005.knitnote.domain.repository.ProjectSegmentRepository {
                    override fun observeByProjectId(projectId: String): kotlinx.coroutines.flow.Flow<List<ProjectSegment>> =
                        kotlinx.coroutines.flow.flowOf(emptyList())

                    override suspend fun getById(id: String): ProjectSegment? = null

                    override suspend fun getByProjectId(projectId: String): List<ProjectSegment> = emptyList()

                    override suspend fun upsert(segment: ProjectSegment): ProjectSegment = throw RuntimeException("db offline")

                    override suspend fun resetSegment(id: String) {}

                    override suspend fun resetProject(projectId: String) {}
                }
            repo.seed(
                chart(
                    "pat-1",
                    listOf(
                        ChartLayer(
                            id = "L1",
                            name = "Main",
                            cells = listOf(ChartCell(symbolId = "jis.knit.k", x = 0, y = 2)),
                        ),
                    ),
                ),
            )
            val viewModel =
                ChartViewerViewModel(
                    patternId = "pat-1",
                    projectId = "proj-1",
                    observeStructuredChart = ObserveStructuredChartUseCase(repo),
                    observeProjectSegments = ObserveProjectSegmentsUseCase(failingSegmentRepo),
                    toggleSegmentState = ToggleSegmentStateUseCase(segmentRepo, authRepository = null),
                    markSegmentDone = MarkSegmentDoneUseCase(segmentRepo, authRepository = null),
                    markRowSegmentsDone =
                        MarkRowSegmentsDoneUseCase(
                            repository = failingSegmentRepo,
                            getStructuredChart = GetStructuredChartByPatternIdUseCase(repo),
                            authRepository = null,
                        ),
                )

            viewModel.onEvent(ChartViewerEvent.MarkRowDone(row = 2))
            advanceUntilIdle()

            assertTrue(viewModel.state.value.errorMessage != null)
        }
}
