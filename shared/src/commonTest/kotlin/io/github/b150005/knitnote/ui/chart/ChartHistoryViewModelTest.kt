package io.github.b150005.knitnote.ui.chart

import io.github.b150005.knitnote.domain.model.ChartCell
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.ChartRevision
import io.github.b150005.knitnote.domain.model.CoordinateSystem
import io.github.b150005.knitnote.domain.model.StorageVariant
import io.github.b150005.knitnote.domain.repository.ChartRevisionRepository
import io.github.b150005.knitnote.domain.usecase.GetChartHistoryUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class ChartHistoryViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var fakeRepo: FakeChartRevisionRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeRepo = FakeChartRevisionRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun makeRevision(
        id: String = "rev-1",
        revisionId: String = id,
        patternId: String = "pat-1",
        parentRevisionId: String? = null,
        commitMessage: String? = null,
        createdAtIso: String = "2026-04-25T10:00:00Z",
    ): ChartRevision =
        ChartRevision(
            id = id,
            patternId = patternId,
            ownerId = "user-1",
            authorId = "user-1",
            schemaVersion = 2,
            storageVariant = StorageVariant.INLINE,
            coordinateSystem = CoordinateSystem.RECT_GRID,
            extents = ChartExtents.Rect(0, 0, 0, 0),
            layers = listOf(ChartLayer(id = "L1", name = "Main", cells = listOf(ChartCell("jis.k1", 0, 0)))),
            revisionId = revisionId,
            parentRevisionId = parentRevisionId,
            contentHash = "h1-test",
            commitMessage = commitMessage,
            createdAt = Instant.parse(createdAtIso),
        )

    private fun createViewModel(patternId: String = "pat-1"): ChartHistoryViewModel =
        ChartHistoryViewModel(
            patternId = patternId,
            getChartHistory = GetChartHistoryUseCase(fakeRepo),
        )

    @Test
    fun `loads revisions newest first from repository observe flow`() =
        runTest {
            // Repository convention is newest-first; the use case forwards as-is.
            fakeRepo.setHistory(
                "pat-1",
                listOf(
                    makeRevision(id = "rev-newest", createdAtIso = "2026-04-25T12:00:00Z", parentRevisionId = "rev-mid"),
                    makeRevision(id = "rev-mid", createdAtIso = "2026-04-25T11:00:00Z", parentRevisionId = "rev-oldest"),
                    makeRevision(id = "rev-oldest", createdAtIso = "2026-04-25T10:00:00Z", parentRevisionId = null),
                ),
            )

            val viewModel = createViewModel()
            val state = viewModel.state.value

            assertFalse(state.isLoading)
            assertEquals(3, state.revisions.size)
            assertEquals("rev-newest", state.revisions[0].id)
            assertEquals("rev-oldest", state.revisions.last().id)
            assertNull(state.error)
        }

    @Test
    fun `empty repository surfaces empty list and clears loading`() =
        runTest {
            val viewModel = createViewModel()
            val state = viewModel.state.value

            assertFalse(state.isLoading)
            assertTrue(state.revisions.isEmpty())
            assertNull(state.error)
        }

    @Test
    fun `repository flow emission updates state live without re-init`() =
        runTest {
            val viewModel = createViewModel()
            assertTrue(
                viewModel.state.value.revisions
                    .isEmpty(),
            )

            // Realtime peer adds a new revision; observeHistoryForPattern emits.
            fakeRepo.setHistory("pat-1", listOf(makeRevision(id = "rev-new")))

            val state = viewModel.state.value
            assertEquals(1, state.revisions.size)
            assertEquals("rev-new", state.revisions.first().id)
        }

    @Test
    fun `flow error surfaces in state and clears loading`() =
        runTest {
            fakeRepo.setObserveError(IllegalStateException("boom"))
            val viewModel = createViewModel()
            val state = viewModel.state.value

            assertFalse(state.isLoading)
            assertNotNull(state.error)
        }

    @Test
    fun `tapRevision emits target with null base when revision is initial commit`() =
        runTest {
            fakeRepo.setHistory(
                "pat-1",
                listOf(makeRevision(id = "rev-tap-target", parentRevisionId = null)),
            )
            val viewModel = createViewModel()

            viewModel.onEvent(ChartHistoryEvent.TapRevision("rev-tap-target"))

            val emitted = viewModel.revisionTaps.first()
            assertEquals(RevisionTapTarget(targetRevisionId = "rev-tap-target", baseRevisionId = null), emitted)
        }

    @Test
    fun `tapRevision resolves base from parentRevisionId of tapped row`() =
        runTest {
            fakeRepo.setHistory(
                "pat-1",
                listOf(
                    makeRevision(id = "rev-newer", parentRevisionId = "rev-older"),
                    makeRevision(id = "rev-older", parentRevisionId = null),
                ),
            )
            val viewModel = createViewModel()

            viewModel.onEvent(ChartHistoryEvent.TapRevision("rev-newer"))

            val emitted = viewModel.revisionTaps.first()
            assertEquals(
                RevisionTapTarget(targetRevisionId = "rev-newer", baseRevisionId = "rev-older"),
                emitted,
            )
        }

    @Test
    fun `tapRevision with unknown revisionId emits null base instead of crashing`() =
        runTest {
            fakeRepo.setHistory("pat-1", listOf(makeRevision(id = "rev-existing")))
            val viewModel = createViewModel()

            viewModel.onEvent(ChartHistoryEvent.TapRevision("rev-not-in-list"))

            // Race condition guard: the Channel still receives a payload so the
            // navigation pipeline does not get wedged silently.
            val emitted = viewModel.revisionTaps.first()
            assertEquals(
                RevisionTapTarget(targetRevisionId = "rev-not-in-list", baseRevisionId = null),
                emitted,
            )
        }

    @Test
    fun `ClearError nulls the surfaced error message`() =
        runTest {
            fakeRepo.setObserveError(IllegalStateException("transient"))
            val viewModel = createViewModel()
            assertNotNull(viewModel.state.value.error)

            viewModel.onEvent(ChartHistoryEvent.ClearError)
            assertNull(viewModel.state.value.error)
        }

    @Test
    fun `viewModel scopes observe to the supplied patternId only`() =
        runTest {
            fakeRepo.setHistory("pat-1", listOf(makeRevision(id = "in-pat-1", patternId = "pat-1")))
            fakeRepo.setHistory("pat-other", listOf(makeRevision(id = "leaked", patternId = "pat-other")))

            val viewModel = createViewModel("pat-1")
            val ids =
                viewModel.state.value.revisions
                    .map { it.id }

            assertEquals(listOf("in-pat-1"), ids)
        }
}

/**
 * Per-patternId in-memory fake. Each `MutableStateFlow` is the live source the
 * use case observes — `setHistory` mutates the flow value to simulate a peer
 * device append landing through Realtime in [ChartRevisionRepositoryImpl].
 *
 * `setObserveError` replaces the next observe call's flow with a flow that
 * throws synchronously on collect, exercising the ViewModel's `catch` branch.
 */
private class FakeChartRevisionRepository : ChartRevisionRepository {
    private val perPattern = mutableMapOf<String, MutableStateFlow<List<ChartRevision>>>()
    private var nextObserveError: Throwable? = null

    fun setHistory(
        patternId: String,
        revisions: List<ChartRevision>,
    ) {
        perPattern.getOrPut(patternId) { MutableStateFlow(emptyList()) }.value = revisions
    }

    fun setObserveError(error: Throwable) {
        nextObserveError = error
    }

    override suspend fun getRevision(revisionId: String): ChartRevision? =
        perPattern.values
            .flatMap { it.value }
            .firstOrNull { it.revisionId == revisionId }

    override suspend fun getHistoryForPattern(
        patternId: String,
        limit: Int,
        offset: Int,
    ): List<ChartRevision> =
        perPattern[patternId]
            ?.value
            .orEmpty()
            .drop(offset)
            .take(limit)

    override fun observeHistoryForPattern(patternId: String): Flow<List<ChartRevision>> {
        val pending = nextObserveError
        if (pending != null) {
            nextObserveError = null
            return kotlinx.coroutines.flow.flow { throw pending }
        }
        return perPattern.getOrPut(patternId) { MutableStateFlow(emptyList()) }
    }

    override suspend fun append(revision: ChartRevision): ChartRevision {
        val flow = perPattern.getOrPut(revision.patternId) { MutableStateFlow(emptyList()) }
        flow.value = listOf(revision) + flow.value
        return revision
    }
}
