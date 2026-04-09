package com.knitnote.ui.activityfeed

import com.knitnote.domain.model.Activity
import com.knitnote.domain.model.ActivityTargetType
import com.knitnote.domain.model.ActivityType
import com.knitnote.domain.model.AuthState
import com.knitnote.domain.usecase.FakeActivityRepository
import com.knitnote.domain.usecase.FakeAuthRepository
import com.knitnote.domain.usecase.GetActivitiesUseCase
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
class ActivityFeedViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var activityRepo: FakeActivityRepository
    private lateinit var authRepo: FakeAuthRepository

    private val now = Instant.fromEpochMilliseconds(1000)

    private fun makeActivity(
        id: String,
        userId: String = "user-1",
        type: ActivityType = ActivityType.STARTED,
        targetType: ActivityTargetType = ActivityTargetType.PROJECT,
        targetId: String = "proj-1",
    ) = Activity(
        id = id,
        userId = userId,
        type = type,
        targetType = targetType,
        targetId = targetId,
        metadata = null,
        createdAt = now,
    )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        activityRepo = FakeActivityRepository()
        authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ActivityFeedViewModel {
        val getActivities = GetActivitiesUseCase(activityRepo)
        return ActivityFeedViewModel(getActivities, authRepo)
    }

    @Test
    fun `loads activities for authenticated user`() = runTest {
        activityRepo.addActivity(makeActivity("a-1"))
        activityRepo.addActivity(makeActivity("a-2"))

        val viewModel = createViewModel()
        val state = viewModel.state.value

        assertFalse(state.isLoading)
        assertEquals(2, state.activities.size)
    }

    @Test
    fun `shows empty state when no activities`() = runTest {
        val viewModel = createViewModel()
        val state = viewModel.state.value

        assertFalse(state.isLoading)
        assertTrue(state.activities.isEmpty())
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
    fun `filters activities by current user`() = runTest {
        activityRepo.addActivity(makeActivity("a-1", userId = "user-1"))
        activityRepo.addActivity(makeActivity("a-2", userId = "other-user"))

        val viewModel = createViewModel()
        val state = viewModel.state.value

        assertEquals(1, state.activities.size)
        assertEquals("a-1", state.activities.first().id)
    }

    @Test
    fun `clears error on ClearError event`() = runTest {
        authRepo.setAuthState(AuthState.Unauthenticated)
        val viewModel = createViewModel()
        assertNotNull(viewModel.state.value.error)

        viewModel.onEvent(ActivityFeedEvent.ClearError)
        assertNull(viewModel.state.value.error)
    }

    @Test
    fun `works with null activity repository`() = runTest {
        val getActivities = GetActivitiesUseCase(null)
        val viewModel = ActivityFeedViewModel(getActivities, authRepo)
        val state = viewModel.state.value

        assertFalse(state.isLoading)
        assertTrue(state.activities.isEmpty())
    }
}
