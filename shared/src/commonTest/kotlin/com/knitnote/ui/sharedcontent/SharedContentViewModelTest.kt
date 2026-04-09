package com.knitnote.ui.sharedcontent

import com.knitnote.domain.model.AuthState
import com.knitnote.domain.model.Difficulty
import com.knitnote.domain.model.Pattern
import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import com.knitnote.domain.model.Share
import com.knitnote.domain.model.SharePermission
import com.knitnote.domain.model.ShareStatus
import com.knitnote.domain.model.Visibility
import com.knitnote.domain.usecase.FakeAuthRepository
import com.knitnote.domain.usecase.FakePatternRepository
import com.knitnote.domain.usecase.FakeProjectRepository
import com.knitnote.domain.usecase.FakeShareRepository
import com.knitnote.domain.usecase.ForkSharedPatternUseCase
import com.knitnote.domain.usecase.ResolveShareTokenUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SharedContentViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var shareRepo: FakeShareRepository
    private lateinit var patternRepo: FakePatternRepository
    private lateinit var projectRepo: FakeProjectRepository
    private lateinit var authRepo: FakeAuthRepository

    private val testPattern = Pattern(
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

    private val testProject = Project(
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

    private val forkableShare = Share(
        id = "share-1",
        patternId = "pat-1",
        fromUserId = "sharer-id",
        toUserId = "user-1",
        permission = SharePermission.FORK,
        status = ShareStatus.ACCEPTED,
        shareToken = "valid-token",
        sharedAt = Instant.fromEpochMilliseconds(1000),
    )

    private val viewOnlyShare = forkableShare.copy(
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
    fun `resolves token and shows pattern`() = runTest {
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
    fun `shows error when token is invalid`() = runTest {
        val viewModel = createViewModel(token = "invalid-token")
        val state = viewModel.state.value

        assertFalse(state.isLoading)
        assertNull(state.pattern)
        assertNotNull(state.error)
    }

    @Test
    fun `fork triggers use case and returns project id`() = runTest {
        patternRepo.create(testPattern)
        projectRepo.create(testProject)
        shareRepo.addShare(forkableShare)

        val viewModel = createViewModel(token = "valid-token")
        assertNull(viewModel.state.value.forkedProjectId)

        viewModel.onEvent(SharedContentEvent.Fork)
        val state = viewModel.state.value

        assertNotNull(state.forkedProjectId)
        assertFalse(state.isForkInProgress)
    }

    @Test
    fun `fork does nothing when share is view-only`() = runTest {
        patternRepo.create(testPattern)
        projectRepo.create(testProject)
        shareRepo.addShare(viewOnlyShare)

        val viewModel = createViewModel(token = "view-token")

        viewModel.onEvent(SharedContentEvent.Fork)
        val state = viewModel.state.value

        assertNull(state.forkedProjectId)
    }

    @Test
    fun `clears error on ClearError event`() = runTest {
        val viewModel = createViewModel(token = "invalid-token")
        assertNotNull(viewModel.state.value.error)

        viewModel.onEvent(SharedContentEvent.ClearError)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `resolves shareId for direct shares`() = runTest {
        val directShare = forkableShare.copy(
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
    fun `handles null share repository gracefully`() = runTest {
        val resolveShareToken = ResolveShareTokenUseCase(null, patternRepo, projectRepo)
        val forkSharedPattern = ForkSharedPatternUseCase(null, patternRepo, projectRepo, authRepo)
        val viewModel = SharedContentViewModel(
            token = "some-token",
            resolveShareToken = resolveShareToken,
            forkSharedPattern = forkSharedPattern,
        )

        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertNotNull(state.error)
    }
}
