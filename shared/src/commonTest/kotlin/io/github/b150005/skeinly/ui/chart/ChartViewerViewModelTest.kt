package io.github.b150005.skeinly.ui.chart

import app.cash.turbine.test
import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import io.github.b150005.skeinly.data.analytics.AnalyticsTracker
import io.github.b150005.skeinly.data.analytics.ChartFormat
import io.github.b150005.skeinly.data.analytics.RecordingAnalyticsTracker
import io.github.b150005.skeinly.data.analytics.SegmentVia
import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.model.Chart
import io.github.b150005.skeinly.domain.model.ChartCell
import io.github.b150005.skeinly.domain.model.ChartExtents
import io.github.b150005.skeinly.domain.model.ChartLayer
import io.github.b150005.skeinly.domain.model.ChartVariation
import io.github.b150005.skeinly.domain.model.CoordinateSystem
import io.github.b150005.skeinly.domain.model.Pattern
import io.github.b150005.skeinly.domain.model.ProjectSegment
import io.github.b150005.skeinly.domain.model.SegmentState
import io.github.b150005.skeinly.domain.model.StorageVariant
import io.github.b150005.skeinly.domain.model.Visibility
import io.github.b150005.skeinly.domain.usecase.ErrorMessage
import io.github.b150005.skeinly.domain.usecase.FakeAuthRepository
import io.github.b150005.skeinly.domain.usecase.FakeChartRepository
import io.github.b150005.skeinly.domain.usecase.FakeChartVariationRepository
import io.github.b150005.skeinly.domain.usecase.FakePatternRepository
import io.github.b150005.skeinly.domain.usecase.FakeProjectSegmentRepository
import io.github.b150005.skeinly.domain.usecase.FakeSuggestionRepository
import io.github.b150005.skeinly.domain.usecase.GetChartByPatternIdUseCase
import io.github.b150005.skeinly.domain.usecase.MarkRowSegmentsDoneUseCase
import io.github.b150005.skeinly.domain.usecase.MarkSegmentDoneUseCase
import io.github.b150005.skeinly.domain.usecase.ObserveChartUseCase
import io.github.b150005.skeinly.domain.usecase.ObserveProjectSegmentsUseCase
import io.github.b150005.skeinly.domain.usecase.OpenSuggestionUseCase
import io.github.b150005.skeinly.domain.usecase.ToggleSegmentStateUseCase
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ChartViewerViewModelTest {
    private val now = Instant.parse("2026-04-18T00:00:00Z")
    private lateinit var repo: FakeChartRepository
    private lateinit var segmentRepo: FakeProjectSegmentRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        repo = FakeChartRepository()
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
    ): Chart =
        Chart(
            id = "chart-$patternId",
            patternId = patternId,
            ownerId = "user-1",
            schemaVersion = Chart.CURRENT_SCHEMA_VERSION,
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
        analyticsTracker: AnalyticsTracker? = null,
    ): ChartViewerViewModel =
        ChartViewerViewModel(
            patternId = patternId,
            projectId = projectId,
            observeChart = ObserveChartUseCase(repo),
            observeProjectSegments = ObserveProjectSegmentsUseCase(segmentRepo),
            toggleSegmentState = ToggleSegmentStateUseCase(segmentRepo, authRepository = null),
            markSegmentDone = MarkSegmentDoneUseCase(segmentRepo, authRepository = null),
            markRowSegmentsDone =
                MarkRowSegmentsDoneUseCase(
                    repository = segmentRepo,
                    getChart = GetChartByPatternIdUseCase(repo),
                    authRepository = null,
                ),
            analyticsTracker = analyticsTracker,
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
                object : io.github.b150005.skeinly.domain.repository.ChartRepository {
                    override suspend fun getByPatternId(patternId: String) = null

                    override fun observeByPatternId(patternId: String) =
                        kotlinx.coroutines.flow.flow<Chart?> {
                            throw IllegalStateException("boom")
                        }

                    override suspend fun existsByPatternId(patternId: String) = false

                    override suspend fun create(chart: Chart) = chart

                    override suspend fun update(chart: Chart) = chart

                    override suspend fun delete(id: String) {}

                    override suspend fun forkFor(
                        sourcePatternId: String,
                        newPatternId: String,
                        newOwnerId: String,
                    ) = null

                    override suspend fun setTip(
                        patternId: String,
                        targetRevision: io.github.b150005.skeinly.domain.model.ChartVersion,
                    ) = null
                }
            val viewModel =
                ChartViewerViewModel(
                    patternId = "pat-1",
                    projectId = null,
                    observeChart = ObserveChartUseCase(failingRepo),
                    observeProjectSegments = ObserveProjectSegmentsUseCase(segmentRepo),
                    toggleSegmentState = ToggleSegmentStateUseCase(segmentRepo, authRepository = null),
                    markSegmentDone = MarkSegmentDoneUseCase(segmentRepo, authRepository = null),
                    markRowSegmentsDone =
                        MarkRowSegmentsDoneUseCase(
                            repository = segmentRepo,
                            getChart = GetChartByPatternIdUseCase(failingRepo),
                            authRepository = null,
                        ),
                )

            viewModel.state.test {
                var seenError = awaitItem()
                while (seenError.errorMessage == null) {
                    seenError = awaitItem()
                }
                assertEquals(ErrorMessage.LoadFailed, seenError.errorMessage)
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
    fun `tapCell is a no-op when the layer is locked`() =
        runTest {
            // Phase 35.2f-ui regression anchor: locked-layer tap-routing is
            // dropped per ADR-011 §5 addendum decision 1(c). The viewer Screen
            // pre-filters locked layers from the tap targets and the ViewModel
            // double-checks; this test exercises the ViewModel-side guard so a
            // future Screen-layer regression can't slip through.
            repo.seed(
                chart(
                    "pat-1",
                    listOf(
                        ChartLayer(
                            id = "L1",
                            name = "Main",
                            cells = listOf(ChartCell("knit", 1, 2)),
                            locked = true,
                        ),
                    ),
                ),
            )
            val viewModel = makeViewModel("pat-1", projectId = "proj-1")
            advanceUntilIdle()

            viewModel.onEvent(ChartViewerEvent.TapCell("L1", 1, 2))
            advanceUntilIdle()

            assertNull(segmentRepo.getById(ProjectSegment.buildId("proj-1", "L1", 1, 2)))
        }

    @Test
    fun `longPressCell is a no-op when the layer is locked`() =
        runTest {
            repo.seed(
                chart(
                    "pat-1",
                    listOf(
                        ChartLayer(
                            id = "L1",
                            name = "Main",
                            cells = listOf(ChartCell("knit", 4, 5)),
                            locked = true,
                        ),
                    ),
                ),
            )
            val viewModel = makeViewModel("pat-1", projectId = "proj-1")
            advanceUntilIdle()

            viewModel.onEvent(ChartViewerEvent.LongPressCell("L1", 4, 5))
            advanceUntilIdle()

            assertNull(segmentRepo.getById(ProjectSegment.buildId("proj-1", "L1", 4, 5)))
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
                object : io.github.b150005.skeinly.domain.repository.ProjectSegmentRepository {
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
                    observeChart = ObserveChartUseCase(repo),
                    observeProjectSegments = ObserveProjectSegmentsUseCase(failingSegmentRepo),
                    toggleSegmentState = ToggleSegmentStateUseCase(segmentRepo, authRepository = null),
                    markSegmentDone = MarkSegmentDoneUseCase(segmentRepo, authRepository = null),
                    markRowSegmentsDone =
                        MarkRowSegmentsDoneUseCase(
                            repository = failingSegmentRepo,
                            getChart = GetChartByPatternIdUseCase(repo),
                            authRepository = null,
                        ),
                )

            viewModel.onEvent(ChartViewerEvent.MarkRowDone(row = 2))
            advanceUntilIdle()

            assertTrue(viewModel.state.value.errorMessage != null)
        }

    // -------------------------------------------------------------------------
    // Phase 38.4.1 (ADR-014 §6) — Open pull request entry from ChartViewer
    // -------------------------------------------------------------------------

    private val testInstant = Instant.parse("2026-04-26T00:00:00Z")

    private fun forkPattern(
        id: String = "fork-pat",
        ownerId: String = "test-user-id",
        parentPatternId: String? = "upstream-pat",
    ): Pattern =
        Pattern(
            id = id,
            ownerId = ownerId,
            title = "Forked pattern",
            description = null,
            difficulty = null,
            gauge = null,
            yarnInfo = null,
            needleSize = null,
            chartImageUrls = emptyList(),
            visibility = Visibility.PRIVATE,
            createdAt = testInstant,
            updatedAt = testInstant,
            parentPatternId = parentPatternId,
        )

    private fun mainBranch(
        patternId: String,
        tipRevisionId: String,
        id: String = "branch-$patternId-main",
        ownerId: String = "test-user-id",
    ): ChartVariation =
        ChartVariation(
            id = id,
            patternId = patternId,
            ownerId = ownerId,
            branchName = ChartVariation.DEFAULT_BRANCH_NAME,
            tipRevisionId = tipRevisionId,
            createdAt = testInstant,
            updatedAt = testInstant,
        )

    private data class OpenPrTestRig(
        val viewModel: ChartViewerViewModel,
        val prRepo: FakeSuggestionRepository,
        val patternRepo: FakePatternRepository,
        val branchRepo: FakeChartVariationRepository,
        val authRepo: FakeAuthRepository,
    )

    private fun makeOpenPrViewModel(
        seedFork: Boolean = true,
        forkOwnerId: String = "test-user-id",
        signedInUserId: String? = "test-user-id",
        seedSourceBranch: Boolean = true,
        seedTargetMain: Boolean = true,
        sourceTipMatchesChart: Boolean = true,
        analyticsTracker: AnalyticsTracker? = null,
    ): OpenPrTestRig {
        val patternRepo = FakePatternRepository()
        val branchRepo = FakeChartVariationRepository()
        val authRepo = FakeAuthRepository()
        val prRepo = FakeSuggestionRepository()
        // Source pattern owns chart "fork-pat" with revision "rev-0".
        val seededChart = chart(patternId = "fork-pat", layers = listOf(ChartLayer(id = "L1", name = "Main")))
        repo.seed(seededChart)
        if (seedFork) {
            patternRepo.seed(forkPattern(id = "fork-pat", ownerId = forkOwnerId))
        }
        if (seedSourceBranch) {
            // Optionally point the branch at a different revision to exercise
            // the "main" fallback path even when there's no exact match.
            val tip = if (sourceTipMatchesChart) seededChart.revisionId else "stale-rev"
            branchRepo.seed(mainBranch(patternId = "fork-pat", tipRevisionId = tip))
        }
        if (seedTargetMain) {
            branchRepo.seed(
                mainBranch(
                    patternId = "upstream-pat",
                    tipRevisionId = "upstream-tip",
                    id = "branch-upstream-main",
                ),
            )
            // Seed the target's history too so OpenSuggestionUseCase's walk
            // finds the source tip as the common ancestor on the first hop.
            // OpenSuggestionUseCase fetches via `getHistoryForPattern`; we
            // inject a custom ChartVersionRepository that returns the source
            // tip as the only entry — sufficient for the happy-path test.
        }
        if (signedInUserId != null) {
            authRepo.setAuthState(AuthState.Authenticated(userId = signedInUserId, email = "u@e"))
        }
        // Build the OpenSuggestionUseCase with a minimal in-test ChartVersionRepository
        // that always claims the source tip is in target history (1-hop common ancestor).
        val chartRevRepo = ForkChartVersionFake(sourceTipRevisionId = seededChart.revisionId)
        val openPrUseCase = OpenSuggestionUseCase(prRepo, chartRevRepo, authRepo)

        val vm =
            ChartViewerViewModel(
                patternId = "fork-pat",
                projectId = null,
                observeChart = ObserveChartUseCase(repo),
                observeProjectSegments = ObserveProjectSegmentsUseCase(segmentRepo),
                toggleSegmentState = ToggleSegmentStateUseCase(segmentRepo, authRepository = null),
                markSegmentDone = MarkSegmentDoneUseCase(segmentRepo, authRepository = null),
                markRowSegmentsDone =
                    MarkRowSegmentsDoneUseCase(
                        repository = segmentRepo,
                        getChart = GetChartByPatternIdUseCase(repo),
                        authRepository = null,
                    ),
                patternRepository = patternRepo,
                chartVariationRepository = branchRepo,
                authRepository = authRepo,
                openSuggestion = openPrUseCase,
                analyticsTracker = analyticsTracker,
            )
        return OpenPrTestRig(vm, prRepo, patternRepo, branchRepo, authRepo)
    }

    @Test
    fun `canOpenSuggestion is true on a fork owned by current user with branches resolved`() =
        runTest {
            val rig = makeOpenPrViewModel()
            advanceUntilIdle()

            val state = rig.viewModel.state.value
            assertTrue(state.canOpenSuggestion)
            assertNotNull(state.pattern)
            assertNotNull(state.currentBranch)
            assertNotNull(state.targetMainBranch)
        }

    @Test
    fun `canOpenSuggestion is false on a non-fork pattern`() =
        runTest {
            val patternRepo = FakePatternRepository()
            val branchRepo = FakeChartVariationRepository()
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated(userId = "test-user-id", email = "u@e"))
            val seededChart = chart("non-fork", listOf(ChartLayer(id = "L1", name = "Main")))
            repo.seed(seededChart)
            patternRepo.seed(forkPattern(id = "non-fork", parentPatternId = null))
            branchRepo.seed(mainBranch(patternId = "non-fork", tipRevisionId = seededChart.revisionId))
            val vm =
                ChartViewerViewModel(
                    patternId = "non-fork",
                    projectId = null,
                    observeChart = ObserveChartUseCase(repo),
                    observeProjectSegments = ObserveProjectSegmentsUseCase(segmentRepo),
                    toggleSegmentState = ToggleSegmentStateUseCase(segmentRepo, authRepository = null),
                    markSegmentDone = MarkSegmentDoneUseCase(segmentRepo, authRepository = null),
                    markRowSegmentsDone =
                        MarkRowSegmentsDoneUseCase(
                            repository = segmentRepo,
                            getChart = GetChartByPatternIdUseCase(repo),
                            authRepository = null,
                        ),
                    patternRepository = patternRepo,
                    chartVariationRepository = branchRepo,
                    authRepository = authRepo,
                )
            advanceUntilIdle()

            assertFalse(vm.state.value.canOpenSuggestion)
        }

    @Test
    fun `canOpenSuggestion is false when current user does not own the pattern`() =
        runTest {
            val rig = makeOpenPrViewModel(forkOwnerId = "someone-else")
            advanceUntilIdle()
            assertFalse(rig.viewModel.state.value.canOpenSuggestion)
        }

    @Test
    fun `canOpenSuggestion is false when target main branch is missing`() =
        runTest {
            val rig = makeOpenPrViewModel(seedTargetMain = false)
            advanceUntilIdle()
            val state = rig.viewModel.state.value
            assertNull(state.targetMainBranch)
            assertFalse(state.canOpenSuggestion)
        }

    @Test
    fun `currentBranch falls back to main but PR-open gate stays closed when tip does not match chart`() =
        runTest {
            val rig = makeOpenPrViewModel(sourceTipMatchesChart = false)
            advanceUntilIdle()
            val state = rig.viewModel.state.value
            // currentBranch resolves to "main" by name (so any rendering of
            // "current branch" stays helpful during cache lag) — see KDoc on
            // `resolveCurrentBranch`.
            val current = state.currentBranch
            assertNotNull(current)
            assertEquals(ChartVariation.DEFAULT_BRANCH_NAME, current.branchName)
            // But the PR-open gate stays CLOSED because the resolved branch's
            // tipRevisionId does not match the loaded chart's revisionId.
            // Without this guard a PR would land with a (sourceBranchId,
            // sourceTipRevisionId) that the merge RPC immediately rejects with
            // "Source tip drifted" — see code review MEDIUM-1.
            assertFalse(state.canOpenSuggestion)
        }

    @Test
    fun `OpenPrTitleChanged and OpenPrDescriptionChanged update drafts`() =
        runTest {
            val rig = makeOpenPrViewModel()
            advanceUntilIdle()

            rig.viewModel.onEvent(ChartViewerEvent.OpenPrTitleChanged("hello"))
            rig.viewModel.onEvent(ChartViewerEvent.OpenPrDescriptionChanged("world"))

            val state = rig.viewModel.state.value
            assertEquals("hello", state.openPrTitleDraft)
            assertEquals("world", state.openPrDescriptionDraft)
        }

    @Test
    fun `RequestOpenSuggestion opens the sheet and clears any prior error`() =
        runTest {
            val rig = makeOpenPrViewModel()
            advanceUntilIdle()
            // Seed an inline error first.
            rig.viewModel.onEvent(ChartViewerEvent.OpenPrTitleChanged(""))
            rig.viewModel.onEvent(ChartViewerEvent.RequestOpenSuggestion)

            val state = rig.viewModel.state.value
            assertTrue(state.pendingOpenPrSheet)
            assertNull(state.openPrError)
        }

    @Test
    fun `DismissOpenSuggestionSheet clears drafts and pending flag`() =
        runTest {
            val rig = makeOpenPrViewModel()
            advanceUntilIdle()
            rig.viewModel.onEvent(ChartViewerEvent.RequestOpenSuggestion)
            rig.viewModel.onEvent(ChartViewerEvent.OpenPrTitleChanged("hi"))
            rig.viewModel.onEvent(ChartViewerEvent.OpenPrDescriptionChanged("there"))
            rig.viewModel.onEvent(ChartViewerEvent.DismissOpenSuggestionSheet)

            val state = rig.viewModel.state.value
            assertFalse(state.pendingOpenPrSheet)
            assertEquals("", state.openPrTitleDraft)
            assertEquals("", state.openPrDescriptionDraft)
        }

    @Test
    fun `ConfirmOpenSuggestion opens PR through use case and emits SuggestionCreated nav event`() =
        runTest {
            val rig = makeOpenPrViewModel()
            advanceUntilIdle()
            rig.viewModel.onEvent(ChartViewerEvent.RequestOpenSuggestion)
            rig.viewModel.onEvent(ChartViewerEvent.OpenPrTitleChanged("Add stitch"))
            rig.viewModel.onEvent(ChartViewerEvent.OpenPrDescriptionChanged("Suggesting an extra row."))

            rig.viewModel.navEvents.test {
                rig.viewModel.onEvent(ChartViewerEvent.ConfirmOpenSuggestion)
                advanceUntilIdle()

                val event = awaitItem()
                assertTrue(event is ChartViewerNavEvent.SuggestionCreated)
                assertNotNull(rig.prRepo.lastOpened)
                assertEquals("Add stitch", rig.prRepo.lastOpened?.title)
                assertEquals("Suggesting an extra row.", rig.prRepo.lastOpened?.description)
                assertEquals("test-user-id", rig.prRepo.lastOpened?.authorId)
                assertEquals("upstream-pat", rig.prRepo.lastOpened?.targetPatternId)
                assertEquals("branch-upstream-main", rig.prRepo.lastOpened?.targetBranchId)
                assertEquals("branch-fork-pat-main", rig.prRepo.lastOpened?.sourceBranchId)

                val state = rig.viewModel.state.value
                assertFalse(state.pendingOpenPrSheet)
                assertFalse(state.isOpeningSuggestion)
                assertEquals("", state.openPrTitleDraft)
                cancelAndConsumeRemainingEvents()
            }
        }

    @Test
    fun `ConfirmOpenSuggestion failure surfaces openPrError and keeps the sheet open`() =
        runTest {
            val rig = makeOpenPrViewModel()
            advanceUntilIdle()
            rig.viewModel.onEvent(ChartViewerEvent.RequestOpenSuggestion)
            rig.viewModel.onEvent(ChartViewerEvent.OpenPrTitleChanged("Add stitch"))
            rig.prRepo.nextOpenError = RuntimeException("network down")

            rig.viewModel.onEvent(ChartViewerEvent.ConfirmOpenSuggestion)
            advanceUntilIdle()

            val state = rig.viewModel.state.value
            assertTrue(state.pendingOpenPrSheet)
            assertFalse(state.isOpeningSuggestion)
            assertNotNull(state.openPrError)
            assertNull(rig.prRepo.lastOpened)
        }

    @Test
    fun `ConfirmOpenSuggestion is a no-op when canOpenSuggestion gate is closed`() =
        runTest {
            // Non-fork pattern → gate closed → submit no-ops without surfacing an
            // error (defensive guard; UI gates the entry too).
            val rig = makeOpenPrViewModel(seedFork = false)
            advanceUntilIdle()
            rig.viewModel.onEvent(ChartViewerEvent.OpenPrTitleChanged("hi"))
            rig.viewModel.onEvent(ChartViewerEvent.ConfirmOpenSuggestion)
            advanceUntilIdle()

            val state = rig.viewModel.state.value
            assertFalse(state.isOpeningSuggestion)
            assertNull(state.openPrError)
            assertNull(rig.prRepo.lastOpened)
        }

    // region Phase F.4 — analytics

    @Test
    fun `tapCell on WIP captures segment_marked_done with via=tap`() =
        runTest {
            repo.seed(chart("pat-1", listOf(ChartLayer(id = "L1", name = "Main"))))
            // Pre-seed segment as WIP so the next tap cycles WIP→DONE.
            segmentRepo.upsert(
                ProjectSegment(
                    id = ProjectSegment.buildId("proj-1", "L1", 1, 2),
                    projectId = "proj-1",
                    layerId = "L1",
                    cellX = 1,
                    cellY = 2,
                    state = SegmentState.WIP,
                    ownerId = "test-user",
                    updatedAt = Instant.parse("2026-04-30T00:00:00Z"),
                ),
            )
            val tracker = RecordingAnalyticsTracker()
            val viewModel = makeViewModel("pat-1", projectId = "proj-1", analyticsTracker = tracker)

            viewModel.onEvent(ChartViewerEvent.TapCell("L1", 1, 2))
            advanceUntilIdle()

            assertEquals(
                listOf<AnalyticsEvent>(AnalyticsEvent.SegmentMarkedDone(via = SegmentVia.Tap)),
                tracker.outcomeEvents,
            )
        }

    @Test
    fun `tapCell on TODO does not capture segment_marked_done`() =
        runTest {
            repo.seed(chart("pat-1", listOf(ChartLayer(id = "L1", name = "Main"))))
            val tracker = RecordingAnalyticsTracker()
            val viewModel = makeViewModel("pat-1", projectId = "proj-1", analyticsTracker = tracker)

            viewModel.onEvent(ChartViewerEvent.TapCell("L1", 1, 2))
            advanceUntilIdle()

            assertTrue(tracker.outcomeEvents.isEmpty(), "tap on TODO transitions to WIP not DONE")
        }

    @Test
    fun `longPressCell captures segment_marked_done with via=long_press on first transition`() =
        runTest {
            repo.seed(chart("pat-1", listOf(ChartLayer(id = "L1", name = "Main"))))
            val tracker = RecordingAnalyticsTracker()
            val viewModel = makeViewModel("pat-1", projectId = "proj-1", analyticsTracker = tracker)

            viewModel.onEvent(ChartViewerEvent.LongPressCell("L1", 4, 5))
            advanceUntilIdle()

            assertEquals(
                listOf<AnalyticsEvent>(AnalyticsEvent.SegmentMarkedDone(via = SegmentVia.LongPress)),
                tracker.outcomeEvents,
            )
        }

    @Test
    fun `longPressCell on already-DONE segment does not capture`() =
        runTest {
            repo.seed(chart("pat-1", listOf(ChartLayer(id = "L1", name = "Main"))))
            segmentRepo.upsert(
                ProjectSegment(
                    id = ProjectSegment.buildId("proj-1", "L1", 4, 5),
                    projectId = "proj-1",
                    layerId = "L1",
                    cellX = 4,
                    cellY = 5,
                    state = SegmentState.DONE,
                    ownerId = "test-user",
                    updatedAt = Instant.parse("2026-04-30T00:00:00Z"),
                ),
            )
            val tracker = RecordingAnalyticsTracker()
            val viewModel = makeViewModel("pat-1", projectId = "proj-1", analyticsTracker = tracker)

            viewModel.onEvent(ChartViewerEvent.LongPressCell("L1", 4, 5))
            advanceUntilIdle()

            assertTrue(
                tracker.outcomeEvents.isEmpty(),
                "long-press on already-DONE is idempotent at the use case but must not fire analytics",
            )
        }

    @Test
    fun `markRowDone captures segment_marked_done with via=row_batch`() =
        runTest {
            repo.seed(
                chart(
                    "pat-1",
                    listOf(
                        ChartLayer(
                            id = "L1",
                            name = "Main",
                            cells = listOf(ChartCell("knit", 0, 2), ChartCell("knit", 1, 2)),
                        ),
                    ),
                ),
            )
            val tracker = RecordingAnalyticsTracker()
            val viewModel = makeViewModel("pat-1", projectId = "proj-1", analyticsTracker = tracker)

            viewModel.onEvent(ChartViewerEvent.MarkRowDone(row = 2))
            advanceUntilIdle()

            assertEquals(
                listOf<AnalyticsEvent>(AnalyticsEvent.SegmentMarkedDone(via = SegmentVia.RowBatch)),
                tracker.outcomeEvents,
                "row-batch fires once regardless of segment count",
            )
        }

    @Test
    fun `successful ConfirmOpenSuggestion captures pull_request_opened with chart_format`() =
        runTest {
            val tracker = RecordingAnalyticsTracker()
            val rig = makeOpenPrViewModel(analyticsTracker = tracker)
            rig.viewModel.onEvent(ChartViewerEvent.OpenPrTitleChanged("PR title"))
            rig.viewModel.onEvent(ChartViewerEvent.ConfirmOpenSuggestion)
            advanceUntilIdle()

            assertNotNull(rig.prRepo.lastOpened, "test fixture sanity: PR should land")
            // The seeded chart uses RECT_GRID by default in [chart].
            assertEquals(
                listOf<AnalyticsEvent>(AnalyticsEvent.SuggestionOpened(chartFormat = ChartFormat.Rect)),
                tracker.outcomeEvents,
            )
        }

    // endregion
}

/**
 * Minimal in-test [io.github.b150005.skeinly.domain.repository.ChartVersionRepository]
 * for `OpenSuggestionUseCase`'s parent-chain walk. Returns a single-entry
 * history for the upstream pattern containing the source tip, so the walk
 * resolves the source tip itself as the common ancestor on the first hop.
 * The full walk algorithm is exercised in `OpenSuggestionUseCaseTest`; this
 * fake covers only what the ViewModel-side happy path needs.
 */
private class ForkChartVersionFake(
    private val sourceTipRevisionId: String,
) : io.github.b150005.skeinly.domain.repository.ChartVersionRepository {
    override suspend fun getRevision(revisionId: String) =
        if (revisionId == sourceTipRevisionId) {
            io.github.b150005.skeinly.domain.model.ChartVersion(
                id = "rev-row-id",
                patternId = "upstream-pat",
                ownerId = "upstream-owner",
                schemaVersion = Chart.CURRENT_SCHEMA_VERSION,
                storageVariant = StorageVariant.INLINE,
                coordinateSystem = CoordinateSystem.RECT_GRID,
                extents = ChartExtents.Rect(0, 0, 0, 0),
                layers = emptyList(),
                revisionId = sourceTipRevisionId,
                parentRevisionId = null,
                authorId = "upstream-owner",
                commitMessage = null,
                contentHash = "h",
                createdAt = Instant.parse("2026-04-26T00:00:00Z"),
            )
        } else {
            null
        }

    override suspend fun getHistoryForPattern(
        patternId: String,
        limit: Int,
        offset: Int,
    ): List<io.github.b150005.skeinly.domain.model.ChartVersion> = listOf(getRevision(sourceTipRevisionId)!!)

    override fun observeHistoryForPattern(
        patternId: String,
    ): kotlinx.coroutines.flow.Flow<List<io.github.b150005.skeinly.domain.model.ChartVersion>> = kotlinx.coroutines.flow.flowOf(emptyList())

    override suspend fun append(revision: io.github.b150005.skeinly.domain.model.ChartVersion) = revision
}
