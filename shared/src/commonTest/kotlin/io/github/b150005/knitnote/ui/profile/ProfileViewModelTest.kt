package io.github.b150005.knitnote.ui.profile

import app.cash.turbine.test
import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.model.User
import io.github.b150005.knitnote.domain.usecase.FakeAuthRepository
import io.github.b150005.knitnote.domain.usecase.FakeUserRepository
import io.github.b150005.knitnote.domain.usecase.GetCurrentUserUseCase
import io.github.b150005.knitnote.domain.usecase.UpdateProfileUseCase
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
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var authRepo: FakeAuthRepository
    private lateinit var userRepo: FakeUserRepository

    private val testUser =
        User(
            id = "user-1",
            displayName = "Test User",
            avatarUrl = null,
            bio = "Hello world",
            createdAt = Clock.System.now(),
        )

    @BeforeTest
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        authRepo = FakeAuthRepository()
        userRepo = FakeUserRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ProfileViewModel {
        val getCurrentUser = GetCurrentUserUseCase(authRepo, userRepo)
        val updateProfile = UpdateProfileUseCase(authRepo, userRepo)
        return ProfileViewModel(getCurrentUser, updateProfile)
    }

    @Test
    fun `loads profile on init when authenticated`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))
            userRepo.addUser(testUser)

            val viewModel = createViewModel()

            val state = viewModel.state.value
            assertFalse(state.isLoading)
            assertNotNull(state.user)
            assertEquals("Test User", state.user?.displayName)
        }

    @Test
    fun `shows error when not authenticated`() =
        runTest {
            val viewModel = createViewModel()

            val state = viewModel.state.value
            assertFalse(state.isLoading)
            assertNull(state.user)
            assertNotNull(state.error)
        }

    @Test
    fun `enters edit mode with current user data`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))
            userRepo.addUser(testUser)

            val viewModel = createViewModel()
            viewModel.onEvent(ProfileEvent.StartEditing)

            val state = viewModel.state.value
            assertTrue(state.isEditing)
            assertEquals("Test User", state.editDisplayName)
            assertEquals("Hello world", state.editBio)
        }

    @Test
    fun `cancels edit mode`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))
            userRepo.addUser(testUser)

            val viewModel = createViewModel()
            viewModel.onEvent(ProfileEvent.StartEditing)
            viewModel.onEvent(ProfileEvent.CancelEditing)

            assertFalse(viewModel.state.value.isEditing)
        }

    @Test
    fun `saves profile successfully`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))
            userRepo.addUser(testUser)

            val viewModel = createViewModel()
            viewModel.onEvent(ProfileEvent.StartEditing)
            viewModel.onEvent(ProfileEvent.UpdateDisplayName("New Name"))
            viewModel.onEvent(ProfileEvent.UpdateBio("New bio"))
            viewModel.onEvent(ProfileEvent.SaveProfile)

            val state = viewModel.state.value
            assertFalse(state.isEditing)
            assertFalse(state.isSaving)
            assertEquals("New Name", state.user?.displayName)
            assertEquals("New bio", state.user?.bio)
        }

    @Test
    fun `shows error when saving with empty display name`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))
            userRepo.addUser(testUser)

            val viewModel = createViewModel()
            viewModel.onEvent(ProfileEvent.StartEditing)
            viewModel.onEvent(ProfileEvent.UpdateDisplayName(""))
            viewModel.onEvent(ProfileEvent.SaveProfile)

            val state = viewModel.state.value
            assertNotNull(state.error)
            assertFalse(state.isSaving)
        }

    @Test
    fun `save profile emits saveSuccess event`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))
            userRepo.addUser(testUser)

            val viewModel = createViewModel()
            viewModel.onEvent(ProfileEvent.StartEditing)
            viewModel.onEvent(ProfileEvent.UpdateDisplayName("New Name"))

            viewModel.saveSuccess.test {
                viewModel.onEvent(ProfileEvent.SaveProfile)
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }
        }
}
