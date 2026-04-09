package com.knitnote.ui.sharedwithme

import com.knitnote.domain.model.AuthState
import com.knitnote.domain.model.Difficulty
import com.knitnote.domain.model.Pattern
import com.knitnote.domain.model.Share
import com.knitnote.domain.model.SharePermission
import com.knitnote.domain.model.ShareStatus
import com.knitnote.domain.model.Visibility
import com.knitnote.domain.usecase.FakeAuthRepository
import com.knitnote.domain.usecase.FakePatternRepository
import com.knitnote.domain.usecase.FakeShareRepository
import com.knitnote.domain.usecase.GetReceivedSharesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SharedWithMeViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var shareRepo: FakeShareRepository
    private lateinit var patternRepo: FakePatternRepository
    private lateinit var authRepo: FakeAuthRepository

    private val testPattern = Pattern(
        id = "pat-1",
        ownerId = "other-user",
        title = "Cable Knit Sweater",
        description = "A cozy cable knit pattern",
        difficulty = Difficulty.INTERMEDIATE,
        gauge = null,
        yarnInfo = null,
        needleSize = null,
        chartImageUrls = emptyList(),
        visibility = Visibility.SHARED,
        createdAt = Instant.fromEpochMilliseconds(1000),
        updatedAt = Instant.fromEpochMilliseconds(2000),
    )

    private fun makeShare(id: String, patternId: String = "pat-1") = Share(
        id = id,
        patternId = patternId,
        fromUserId = "other-user",
        toUserId = "user-1",
        permission = SharePermission.VIEW,
        status = ShareStatus.ACCEPTED,
        shareToken = "token-$id",
        sharedAt = Instant.fromEpochMilliseconds(1000),
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        shareRepo = FakeShareRepository()
        patternRepo = FakePatternRepository()
        authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SharedWithMeViewModel {
        val useCase = GetReceivedSharesUseCase(shareRepo, authRepo)
        return SharedWithMeViewModel(useCase, patternRepo)
    }

    @Test
    fun `loads shares and resolves pattern titles`() = runTest {
        patternRepo.create(testPattern)
        shareRepo.addShare(makeShare("s-1"))

        val viewModel = createViewModel()
        val state = viewModel.state.value

        assertFalse(state.isLoading)
        assertEquals(1, state.shares.size)
        assertEquals("Cable Knit Sweater", state.patternTitles["pat-1"])
    }

    @Test
    fun `shows empty map when pattern not found`() = runTest {
        shareRepo.addShare(makeShare("s-1", patternId = "unknown-pattern"))

        val viewModel = createViewModel()
        val state = viewModel.state.value

        assertFalse(state.isLoading)
        assertEquals(1, state.shares.size)
        assertNull(state.patternTitles["unknown-pattern"])
    }

    @Test
    fun `resolves multiple distinct pattern titles`() = runTest {
        val pattern2 = testPattern.copy(id = "pat-2", title = "Striped Scarf")
        patternRepo.create(testPattern)
        patternRepo.create(pattern2)
        shareRepo.addShare(makeShare("s-1", patternId = "pat-1"))
        shareRepo.addShare(makeShare("s-2", patternId = "pat-2"))

        val viewModel = createViewModel()
        val state = viewModel.state.value

        assertEquals(2, state.shares.size)
        assertEquals("Cable Knit Sweater", state.patternTitles["pat-1"])
        assertEquals("Striped Scarf", state.patternTitles["pat-2"])
    }

    @Test
    fun `shows empty state when no shares`() = runTest {
        val viewModel = createViewModel()
        val state = viewModel.state.value

        assertFalse(state.isLoading)
        assertTrue(state.shares.isEmpty())
        assertTrue(state.patternTitles.isEmpty())
    }

    @Test
    fun `shows error when not authenticated`() = runTest {
        authRepo.setAuthState(AuthState.Unauthenticated)

        val viewModel = createViewModel()
        val state = viewModel.state.value

        assertFalse(state.isLoading)
        assertNotNull(state.error)
    }

    @Test
    fun `clears error on ClearError event`() = runTest {
        authRepo.setAuthState(AuthState.Unauthenticated)

        val viewModel = createViewModel()
        assertNotNull(viewModel.state.value.error)

        viewModel.onEvent(SharedWithMeEvent.ClearError)
        assertNull(viewModel.state.value.error)
    }
}
