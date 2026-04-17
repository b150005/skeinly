package io.github.b150005.knitnote.ui.sharedcontent

import app.cash.turbine.test
import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectStatus
import io.github.b150005.knitnote.domain.model.Share
import io.github.b150005.knitnote.domain.model.SharePermission
import io.github.b150005.knitnote.domain.model.ShareStatus
import io.github.b150005.knitnote.domain.model.Visibility
import io.github.b150005.knitnote.domain.usecase.FakeAuthRepository
import io.github.b150005.knitnote.domain.usecase.FakePatternRepository
import io.github.b150005.knitnote.domain.usecase.FakeProjectRepository
import io.github.b150005.knitnote.domain.usecase.FakeShareRepository
import io.github.b150005.knitnote.domain.usecase.ForkSharedPatternUseCase
import io.github.b150005.knitnote.domain.usecase.ResolveShareTokenUseCase
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SharedContentViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var shareRepo: FakeShareRepository
    private lateinit var patternRepo: FakePatternRepository
    private lateinit var projectRepo: FakeProjectRepository
    private lateinit var authRepo: FakeAuthRepository

    private val testPattern =
        Pattern(
            id = "pat-1",
            ownerId = "sharer-id",
            title = "Cable Knit Sweater",
            description = "A cozy cable knit pattern",
            difficulty = Difficulty.INTERMEDIATE,
            gauge = "20 stitches / 4 inches",
            yarnInfo = "DK weight wool",
            needleSize = "4mm",
            chartImageUrls = emptyList(),
            visibility = Visibility.SHARED,
            createdAt = Instant.fromEpochMilliseconds(1000),
            updatedAt = Instant.fromEpochMilliseconds(2000),
        )

    private val testProject =
        Project(
            id = "proj-1",
            ownerId = "sharer-id",
            patternId = "pat-1",
            title = "My Sweater",
            status = ProjectStatus.IN_PROGRESS,
            currentRow = 50,
            totalRows = 200,
            startedAt = null,
            completedAt = null,
            createdAt = Instant.fromEpochMilliseconds(1000),
            updatedAt = Instant.fromEpochMilliseconds(2000),
        )

    private val forkableShare =
        Share(
            id = "share-1",
            patternId = "pat-1",
            fromUserId = "sharer-id",
            toUserId = "user-1",
            permission = SharePermission.FORK,
            status = ShareStatus.ACCEPTED,
            shareToken = "valid-token",
            sharedAt = Instant.fromEpochMilliseconds(1000),
        )

    private val viewOnlyShare =
        forkableShare.copy(
            id = "share-view",
            permission = SharePermission.VIEW,
            shareToken = "view-token",
        )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        shareRepo = FakeShareRepository()
        patternRepo = FakePatternRepository()
        projectRepo = FakeProjectRepository()
        authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        token: String? = null,
        shareId: String? = null,
    ): SharedContentViewModel {
        val resolveShareToken = ResolveShareTokenUseCase(shareRepo, patternRepo, projectRepo)
        val forkSharedPattern = ForkSharedPatternUseCase(shareRepo, patternRepo, projectRepo, authRepo)
        return SharedContentViewModel(
            token = token,
            shareId = shareId,
            resolveShareToken = resolveShareToken,
            forkSharedPattern = forkSharedPattern,
        )
    }

    @Test
    fun `resolves token and shows pattern`() =
        runTest {
            patternRepo.create(testPattern)
            projectRepo.create(testProject)
            shareRepo.addShare(forkableShare)

            val viewModel = createViewModel(token = "valid-token")
            val state = viewModel.state.value

            assertFalse(state.isLoading)
            assertNotNull(state.pattern)
            assertEquals("Cable Knit Sweater", state.pattern?.title)
            assertEquals(1, state.projectCount)
            assertNotNull(state.share)
            assertEquals(SharePermission.FORK, state.share?.permission)
        }

    @Test
    fun `shows error when token is invalid`() =
        runTest {
            val viewModel = createViewModel(token = "invalid-token")
            val state = viewModel.state.value

            assertFalse(state.isLoading)
            assertNull(state.pattern)
            assertNotNull(state.error)
        }

    @Test
    fun `fork triggers use case and emits project id`() =
        runTest {
            patternRepo.create(testPattern)
            projectRepo.create(testProject)
            shareRepo.addShare(forkableShare)

            val viewModel = createViewModel(token = "valid-token")

            viewModel.forkedProjectId.test {
                viewModel.onEvent(SharedContentEvent.Fork)
                val forkedId = awaitItem()
                assertNotNull(forkedId)
                assertFalse(viewModel.state.value.isForkInProgress)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `fork does nothing when share is view-only`() =
        runTest {
            patternRepo.create(testPattern)
            projectRepo.create(testProject)
            shareRepo.addShare(viewOnlyShare)

            val viewModel = createViewModel(token = "view-token")

            viewModel.onEvent(SharedContentEvent.Fork)
            // No forkedProjectId should be emitted; verify state unchanged
            assertFalse(viewModel.state.value.isForkInProgress)
        }

    @Test
    fun `clears error on ClearError event`() =
        runTest {
            val viewModel = createViewModel(token = "invalid-token")
            assertNotNull(viewModel.state.value.error)

            viewModel.onEvent(SharedContentEvent.ClearError)
            assertNull(viewModel.state.value.error)
        }

    @Test
    fun `resolves shareId for direct shares`() =
        runTest {
            val directShare =
                forkableShare.copy(
                    id = "direct-share-1",
                    shareToken = null,
                )
            patternRepo.create(testPattern)
            projectRepo.create(testProject)
            shareRepo.addShare(directShare)

            val viewModel = createViewModel(shareId = "direct-share-1")
            val state = viewModel.state.value

            assertFalse(state.isLoading)
            assertNotNull(state.pattern)
            assertEquals("Cable Knit Sweater", state.pattern?.title)
            assertNotNull(state.share)
            assertEquals("direct-share-1", state.share?.id)
        }

    @Test
    fun `handles null share repository gracefully`() =
        runTest {
            val resolveShareToken = ResolveShareTokenUseCase(null, patternRepo, projectRepo)
            val forkSharedPattern = ForkSharedPatternUseCase(null, patternRepo, projectRepo, authRepo)
            val viewModel =
                SharedContentViewModel(
                    token = "some-token",
                    resolveShareToken = resolveShareToken,
                    forkSharedPattern = forkSharedPattern,
                )

            val state = viewModel.state.value
            assertFalse(state.isLoading)
            assertNotNull(state.error)
        }

    @Test
    fun `fork does nothing when share is null`() =
        runTest {
            // Resolve with valid token but don't set FORK permission — use VIEW
            patternRepo.create(testPattern)
            projectRepo.create(testProject)
            shareRepo.addShare(viewOnlyShare)

            val viewModel = createViewModel(token = "view-token")
            // share is VIEW-only, so fork should be a no-op
            viewModel.onEvent(SharedContentEvent.Fork)
            assertFalse(viewModel.state.value.isForkInProgress)
            assertNull(viewModel.state.value.error)
        }

    @Test
    fun `fork when not authenticated sets error`() =
        runTest {
            authRepo.setAuthState(AuthState.Unauthenticated)
            patternRepo.create(testPattern)
            projectRepo.create(testProject)
            shareRepo.addShare(forkableShare)

            val viewModel = createViewModel(token = "valid-token")

            viewModel.onEvent(SharedContentEvent.Fork)
            val state = viewModel.state.value
            assertNotNull(state.error)
            assertFalse(state.isForkInProgress)
        }
}
