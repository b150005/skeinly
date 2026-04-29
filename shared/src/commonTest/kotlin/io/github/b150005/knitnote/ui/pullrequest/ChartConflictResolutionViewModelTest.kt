package io.github.b150005.knitnote.ui.pullrequest

import io.github.b150005.knitnote.data.analytics.AnalyticsEvents
import io.github.b150005.knitnote.data.analytics.AnalyticsTracker
import io.github.b150005.knitnote.data.analytics.RecordingAnalyticsTracker
import io.github.b150005.knitnote.domain.chart.CellCoordinate
import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.model.ChartCell
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.ChartRevision
import io.github.b150005.knitnote.domain.model.CoordinateSystem
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.model.PullRequestStatus
import io.github.b150005.knitnote.domain.model.StorageVariant
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.model.Visibility
import io.github.b150005.knitnote.domain.repository.ChartRevisionRepository
import io.github.b150005.knitnote.domain.repository.PullRequestMergeOperations
import io.github.b150005.knitnote.domain.usecase.ConflictResolution
import io.github.b150005.knitnote.domain.usecase.FakeAuthRepository
import io.github.b150005.knitnote.domain.usecase.FakePatternRepository
import io.github.b150005.knitnote.domain.usecase.FakePullRequestRepository
import io.github.b150005.knitnote.domain.usecase.FakeStructuredChartRepository
import io.github.b150005.knitnote.domain.usecase.GetPullRequestUseCase
import io.github.b150005.knitnote.domain.usecase.MergePullRequestUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Phase 38.4 (ADR-014 §6) coverage matrix for [ChartConflictResolutionViewModel],
 * extended in Phase F.5 with the analytics-capture regression anchors:
 *
 *  1. loadInitial resolves ancestor / theirs / mine and runs ConflictDetector
 *  2. canApplyAndMerge starts false when conflicts exist
 *  3. canApplyAndMerge flips true when every cell conflict has a pick
 *  4. canApplyAndMerge stays false if a layer conflict is unresolved
 *  5. PickCell records resolution and re-evaluates canApplyAndMerge
 *  6. PickLayer records resolution and re-evaluates canApplyAndMerge
 *  7. ApplyAndMerge invokes MergePullRequestUseCase + emits MergeApplied event
 *  8. ApplyAndMerge surfaces UseCase failure as state error
 *  9. revision lookup miss surfaces error and leaves report null
 * 10. ClearError nulls the error message
 * 11. successful ApplyAndMerge captures pull_request_merged with had_conflicts=true
 * 12. failed ApplyAndMerge does NOT capture pull_request_merged
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChartConflictResolutionViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private val now = Instant.parse("2026-04-26T10:00:00Z")
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun pr(): PullRequest =
        PullRequest(
            id = "pr-1",
            sourcePatternId = "pat-fork",
            sourceBranchId = "branch-source",
            sourceTipRevisionId = "rev-theirs",
            targetPatternId = "pat-upstream",
            targetBranchId = "branch-target",
            commonAncestorRevisionId = "rev-ancestor",
            authorId = "contributor",
            title = "Fix row 12",
            description = null,
            status = PullRequestStatus.OPEN,
            mergedRevisionId = null,
            mergedAt = null,
            closedAt = null,
            createdAt = now,
            updatedAt = now,
        )

    private fun targetPattern(): Pattern =
        Pattern(
            id = "pat-upstream",
            ownerId = "owner-id",
            title = "Upstream",
            description = null,
            difficulty = null,
            gauge = null,
            yarnInfo = null,
            needleSize = null,
            chartImageUrls = emptyList(),
            visibility = Visibility.PUBLIC,
            createdAt = now,
            updatedAt = now,
        )

    private fun chartFor(
        revisionId: String,
        layers: List<ChartLayer>,
        patternId: String = "pat-upstream",
    ): StructuredChart =
        StructuredChart(
            id = "chart-$revisionId",
            patternId = patternId,
            ownerId = "owner-id",
            schemaVersion = 2,
            storageVariant = StorageVariant.INLINE,
            coordinateSystem = CoordinateSystem.RECT_GRID,
            extents = ChartExtents.Rect(minX = 0, maxX = 4, minY = 0, maxY = 4),
            layers = layers,
            revisionId = revisionId,
            parentRevisionId = null,
            contentHash = "h1-$revisionId",
            createdAt = now,
            updatedAt = now,
        )

    private fun revisionFor(
        revisionId: String,
        layers: List<ChartLayer>,
    ): ChartRevision =
        ChartRevision(
            id = revisionId,
            patternId = "pat-upstream",
            ownerId = "owner-id",
            authorId = "owner-id",
            schemaVersion = 2,
            storageVariant = StorageVariant.INLINE,
            coordinateSystem = CoordinateSystem.RECT_GRID,
            extents = ChartExtents.Rect(minX = 0, maxX = 4, minY = 0, maxY = 4),
            layers = layers,
            revisionId = revisionId,
            parentRevisionId = null,
            contentHash = "h1-$revisionId",
            commitMessage = null,
            createdAt = now,
        )

    private fun setupHarness(
        ancestorLayers: List<ChartLayer>,
        theirsLayers: List<ChartLayer>,
        mineLayers: List<ChartLayer>,
        signedInAs: String = "owner-id",
        mergeOps: PullRequestMergeOperations? = NoOpMergeOps(),
        analyticsTracker: AnalyticsTracker? = null,
    ): Harness {
        val prRepo =
            FakePullRequestRepository().apply {
                seedById(pr())
            }
        val revisionRepo =
            FakeRevisionRepoForResolution(
                revisions =
                    listOf(
                        revisionFor("rev-ancestor", ancestorLayers),
                        revisionFor("rev-theirs", theirsLayers),
                    ),
            )
        val chartRepo =
            FakeStructuredChartRepository().apply {
                seed(chartFor(revisionId = "rev-mine", layers = mineLayers))
            }
        val auth =
            FakeAuthRepository().apply {
                setAuthState(AuthState.Authenticated(userId = signedInAs, email = "u@x"))
            }
        val patterns =
            FakePatternRepository().apply { seed(targetPattern()) }
        val merge = MergePullRequestUseCase(mergeOps, patterns, auth, json)

        val viewModel =
            ChartConflictResolutionViewModel(
                prId = "pr-1",
                getPullRequest = GetPullRequestUseCase(prRepo),
                chartRevisionRepository = revisionRepo,
                structuredChartRepository = chartRepo,
                mergePullRequest = merge,
                analyticsTracker = analyticsTracker,
            )
        return Harness(viewModel = viewModel, mergeOps = mergeOps)
    }

    private data class Harness(
        val viewModel: ChartConflictResolutionViewModel,
        val mergeOps: PullRequestMergeOperations?,
    )

    @Test
    fun `loadInitial resolves three snapshots and runs detector`() =
        runTest {
            val harness =
                setupHarness(
                    ancestorLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))),
                    theirsLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.p1", 0, 0)))),
                    mineLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.yo", 0, 0)))),
                )
            advanceUntilIdle()

            val state = harness.viewModel.state.value
            assertNotNull(state.ancestor)
            assertNotNull(state.theirs)
            assertNotNull(state.mine)
            val report = state.report
            assertNotNull(report)
            assertFalse(state.isLoading)
            assertEquals(1, report.conflicts.size)
        }

    @Test
    fun `canApplyAndMerge is false until every conflict has a pick`() =
        runTest {
            val harness =
                setupHarness(
                    ancestorLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))),
                    theirsLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.p1", 0, 0)))),
                    mineLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.yo", 0, 0)))),
                )
            advanceUntilIdle()

            assertFalse(harness.viewModel.state.value.canApplyAndMerge)
        }

    @Test
    fun `PickCell flips canApplyAndMerge true once all cell conflicts resolved`() =
        runTest {
            val harness =
                setupHarness(
                    ancestorLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))),
                    theirsLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.p1", 0, 0)))),
                    mineLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.yo", 0, 0)))),
                )
            advanceUntilIdle()

            harness.viewModel.onEvent(
                ChartConflictResolutionEvent.PickCell(
                    coordinate = CellCoordinate("L1", 0, 0),
                    resolution = ConflictResolution.TAKE_THEIRS,
                ),
            )
            advanceUntilIdle()

            assertTrue(harness.viewModel.state.value.canApplyAndMerge)
        }

    @Test
    fun `PickLayer flips canApplyAndMerge true once all layer conflicts resolved`() =
        runTest {
            // Layer rename conflict + no cell conflicts.
            val harness =
                setupHarness(
                    ancestorLayers = listOf(ChartLayer(id = "L1", name = "Main")),
                    theirsLayers = listOf(ChartLayer(id = "L1", name = "Cable")),
                    mineLayers = listOf(ChartLayer(id = "L1", name = "Lace")),
                )
            advanceUntilIdle()

            assertFalse(harness.viewModel.state.value.canApplyAndMerge)

            harness.viewModel.onEvent(
                ChartConflictResolutionEvent.PickLayer(
                    layerId = "L1",
                    resolution = ConflictResolution.KEEP_MINE,
                ),
            )
            advanceUntilIdle()

            assertTrue(harness.viewModel.state.value.canApplyAndMerge)
        }

    @Test
    fun `ApplyAndMerge invokes the merge use case and emits MergeApplied`() =
        runTest {
            val ops = RecordingMergeOps()
            val harness =
                setupHarness(
                    ancestorLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))),
                    theirsLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.p1", 0, 0)))),
                    mineLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.yo", 0, 0)))),
                    mergeOps = ops,
                )
            advanceUntilIdle()

            harness.viewModel.onEvent(
                ChartConflictResolutionEvent.PickCell(
                    coordinate = CellCoordinate("L1", 0, 0),
                    resolution = ConflictResolution.TAKE_THEIRS,
                ),
            )
            harness.viewModel.onEvent(ChartConflictResolutionEvent.ApplyAndMerge)

            val event = harness.viewModel.navEvents.first()
            assertEquals(1, ops.callCount)
            assertTrue(event is ChartConflictResolutionNavEvent.MergeApplied)
        }

    @Test
    fun `ApplyAndMerge surfaces use case failure as state error`() =
        runTest {
            val ops = RecordingMergeOps(throwOnNext = RuntimeException("PR not open"))
            val harness =
                setupHarness(
                    ancestorLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))),
                    theirsLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.p1", 0, 0)))),
                    mineLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.yo", 0, 0)))),
                    mergeOps = ops,
                )
            advanceUntilIdle()

            harness.viewModel.onEvent(
                ChartConflictResolutionEvent.PickCell(
                    coordinate = CellCoordinate("L1", 0, 0),
                    resolution = ConflictResolution.TAKE_THEIRS,
                ),
            )
            harness.viewModel.onEvent(ChartConflictResolutionEvent.ApplyAndMerge)
            advanceUntilIdle()

            val state = harness.viewModel.state.value
            assertFalse(state.isMerging)
            assertNotNull(state.error)
        }

    @Test
    fun `revision lookup miss leaves report null and surfaces error`() =
        runTest {
            // Don't seed the chart repo so `mine` lookup returns null.
            val prRepo =
                FakePullRequestRepository().apply { seedById(pr()) }
            val revisionRepo =
                FakeRevisionRepoForResolution(
                    revisions = emptyList(), // no ancestor or theirs
                )
            val chartRepo = FakeStructuredChartRepository()
            val auth =
                FakeAuthRepository().apply {
                    setAuthState(AuthState.Authenticated(userId = "owner-id", email = "u@x"))
                }
            val patterns = FakePatternRepository().apply { seed(targetPattern()) }
            val merge = MergePullRequestUseCase(NoOpMergeOps(), patterns, auth, json)

            val viewModel =
                ChartConflictResolutionViewModel(
                    prId = "pr-1",
                    getPullRequest = GetPullRequestUseCase(prRepo),
                    chartRevisionRepository = revisionRepo,
                    structuredChartRepository = chartRepo,
                    mergePullRequest = merge,
                )
            advanceUntilIdle()

            val state = viewModel.state.value
            assertNull(state.report)
            assertNotNull(state.error)
        }

    @Test
    fun `successful ApplyAndMerge captures pull_request_merged with had_conflicts true`() =
        runTest {
            val tracker = RecordingAnalyticsTracker()
            val harness =
                setupHarness(
                    ancestorLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))),
                    theirsLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.p1", 0, 0)))),
                    mineLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.yo", 0, 0)))),
                    analyticsTracker = tracker,
                )
            advanceUntilIdle()

            harness.viewModel.onEvent(
                ChartConflictResolutionEvent.PickCell(
                    coordinate = CellCoordinate("L1", 0, 0),
                    resolution = ConflictResolution.TAKE_THEIRS,
                ),
            )
            harness.viewModel.onEvent(ChartConflictResolutionEvent.ApplyAndMerge)
            advanceUntilIdle()

            assertEquals(1, tracker.captured.size)
            val event = tracker.captured.single()
            assertEquals(AnalyticsEvents.PULL_REQUEST_MERGED, event.name)
            assertEquals(mapOf(AnalyticsEvents.Props.HAD_CONFLICTS to true), event.properties)
            // Anchor the "capture BEFORE _navEvents.trySend" invariant: if the
            // ordering ever flips silently in applyAndMerge(), the nav event
            // would still emit but the analytics-test contract would still
            // pass. Asserting the nav event here makes the merge happy-path
            // observable end-to-end from this test.
            val navEvent = harness.viewModel.navEvents.first()
            assertTrue(navEvent is ChartConflictResolutionNavEvent.MergeApplied)
        }

    @Test
    fun `failed ApplyAndMerge does not capture pull_request_merged event`() =
        runTest {
            val tracker = RecordingAnalyticsTracker()
            val ops = RecordingMergeOps(throwOnNext = RuntimeException("Source tip drifted"))
            val harness =
                setupHarness(
                    ancestorLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))),
                    theirsLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.p1", 0, 0)))),
                    mineLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.yo", 0, 0)))),
                    mergeOps = ops,
                    analyticsTracker = tracker,
                )
            advanceUntilIdle()

            harness.viewModel.onEvent(
                ChartConflictResolutionEvent.PickCell(
                    coordinate = CellCoordinate("L1", 0, 0),
                    resolution = ConflictResolution.TAKE_THEIRS,
                ),
            )
            harness.viewModel.onEvent(ChartConflictResolutionEvent.ApplyAndMerge)
            advanceUntilIdle()

            assertTrue(tracker.captured.isEmpty())
            assertNotNull(harness.viewModel.state.value.error)
        }

    @Test
    fun `ClearError nulls the error message`() =
        runTest {
            val ops = RecordingMergeOps(throwOnNext = RuntimeException("PR not open"))
            val harness =
                setupHarness(
                    ancestorLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))),
                    theirsLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.p1", 0, 0)))),
                    mineLayers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.yo", 0, 0)))),
                    mergeOps = ops,
                )
            advanceUntilIdle()

            harness.viewModel.onEvent(
                ChartConflictResolutionEvent.PickCell(
                    coordinate = CellCoordinate("L1", 0, 0),
                    resolution = ConflictResolution.TAKE_THEIRS,
                ),
            )
            harness.viewModel.onEvent(ChartConflictResolutionEvent.ApplyAndMerge)
            advanceUntilIdle()

            harness.viewModel.onEvent(ChartConflictResolutionEvent.ClearError)
            advanceUntilIdle()

            assertNull(harness.viewModel.state.value.error)
        }

    private class NoOpMergeOps : PullRequestMergeOperations {
        override suspend fun merge(
            pullRequestId: String,
            strategy: String,
            mergedDocument: JsonElement,
            mergedContentHash: String,
            resolvedRevisionId: String,
        ): String = resolvedRevisionId
    }

    private class RecordingMergeOps(
        var throwOnNext: Throwable? = null,
    ) : PullRequestMergeOperations {
        var callCount = 0
            private set

        override suspend fun merge(
            pullRequestId: String,
            strategy: String,
            mergedDocument: JsonElement,
            mergedContentHash: String,
            resolvedRevisionId: String,
        ): String {
            callCount += 1
            throwOnNext?.let {
                throwOnNext = null
                throw it
            }
            return resolvedRevisionId
        }
    }

    private class FakeRevisionRepoForResolution(
        revisions: List<ChartRevision>,
    ) : ChartRevisionRepository {
        private val byId = revisions.associateBy { it.revisionId }.toMutableMap()
        private val flow = MutableStateFlow(revisions)

        override suspend fun getRevision(revisionId: String): ChartRevision? = byId[revisionId]

        override suspend fun getHistoryForPattern(
            patternId: String,
            limit: Int,
            offset: Int,
        ): List<ChartRevision> =
            flow.value
                .filter { it.patternId == patternId }
                .drop(offset)
                .take(limit)

        override fun observeHistoryForPattern(patternId: String): Flow<List<ChartRevision>> = flow

        override suspend fun append(revision: ChartRevision): ChartRevision = error("not used")
    }
}
