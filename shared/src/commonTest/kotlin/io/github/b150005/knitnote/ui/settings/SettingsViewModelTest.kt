package io.github.b150005.knitnote.ui.settings

import app.cash.turbine.test
import io.github.b150005.knitnote.data.preferences.AnalyticsPreferences
import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.usecase.CloseRealtimeChannelsUseCase
import io.github.b150005.knitnote.domain.usecase.DeleteAccountUseCase
import io.github.b150005.knitnote.domain.usecase.FakeAuthRepository
import io.github.b150005.knitnote.domain.usecase.ObserveAuthStateUseCase
import io.github.b150005.knitnote.domain.usecase.SignOutUseCase
import io.github.b150005.knitnote.domain.usecase.UpdateEmailUseCase
import io.github.b150005.knitnote.domain.usecase.UpdatePasswordUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var authRepo: FakeAuthRepository
    private lateinit var analyticsPrefs: FakeAnalyticsPreferences

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        authRepo = FakeAuthRepository()
        analyticsPrefs = FakeAnalyticsPreferences()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SettingsViewModel =
        SettingsViewModel(
            observeAuthState = ObserveAuthStateUseCase(authRepo),
            signOut = SignOutUseCase(authRepo, CloseRealtimeChannelsUseCase(null, null, null)),
            deleteAccount = DeleteAccountUseCase(authRepo, CloseRealtimeChannelsUseCase(null, null, null)),
            updatePassword = UpdatePasswordUseCase(authRepo),
            updateEmail = UpdateEmailUseCase(authRepo),
            analyticsPreferences = analyticsPrefs,
        )

    @Test
    fun `initial state loads email from auth state`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))
            val viewModel = createViewModel()
            viewModel.state.test {
                val state = awaitItem()
                assertEquals("test@example.com", state.email)
                assertFalse(state.isLoading)
            }
        }

    @Test
    fun `initial state with unauthenticated has null email`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.state.test {
                val state = awaitItem()
                assertNull(state.email)
                assertFalse(state.isLoading)
            }
        }

    @Test
    fun `sign out triggers auth state change`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))
            val viewModel = createViewModel()
            viewModel.onEvent(SettingsEvent.SignOut)
            // After sign out, auth state observer emits Unauthenticated
            assertNull(authRepo.getCurrentUserId())
        }

    @Test
    fun `sign out failure shows error`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))
            authRepo.signOutError = RuntimeException("Network error")
            val viewModel = createViewModel()
            viewModel.onEvent(SettingsEvent.SignOut)
            viewModel.state.test {
                val state = awaitItem()
                assertNotNull(state.error)
            }
        }

    @Test
    fun `delete account confirmed emits accountDeleted event`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))
            val viewModel = createViewModel()
            viewModel.accountDeleted.test {
                viewModel.onEvent(SettingsEvent.DeleteAccountConfirmed)
                awaitItem()
            }
        }

    @Test
    fun `delete account shows isDeletingAccount state`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))
            // Set an error so deletion fails and we can observe isDeletingAccount reset
            authRepo.deleteAccountError = RuntimeException("Server error")
            val viewModel = createViewModel()
            viewModel.onEvent(SettingsEvent.DeleteAccountConfirmed)
            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isDeletingAccount)
                assertNotNull(state.error)
            }
        }

    @Test
    fun `delete account failure shows error`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))
            authRepo.deleteAccountError = RuntimeException("Server error")
            val viewModel = createViewModel()
            viewModel.onEvent(SettingsEvent.DeleteAccountConfirmed)
            viewModel.state.test {
                val state = awaitItem()
                assertNotNull(state.error)
                assertFalse(state.isDeletingAccount)
            }
        }

    @Test
    fun `clear error resets error state`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))
            authRepo.deleteAccountError = RuntimeException("err")
            val viewModel = createViewModel()
            viewModel.onEvent(SettingsEvent.DeleteAccountConfirmed)
            viewModel.onEvent(SettingsEvent.ClearError)
            viewModel.state.test {
                assertNull(awaitItem().error)
            }
        }

    @Test
    fun `analytics opt-in defaults to false and reflects preference`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.state.test {
                // Initial collection: default OFF (Phase 27a no-tracking stance).
                assertFalse(awaitItem().analyticsOptIn)
            }
        }

    @Test
    fun `analytics opt-in mirrors persisted true preference at init`() =
        runTest {
            // Persisted ON (returning user who consented previously). The
            // ViewModel must observe the StateFlow eagerly so the Settings
            // toggle paints in its correct ON position before first frame.
            analyticsPrefs.setAnalyticsOptIn(true)
            val viewModel = createViewModel()
            viewModel.state.test {
                assertTrue(awaitItem().analyticsOptIn)
            }
        }

    @Test
    fun `SetAnalyticsOptIn writes preference and updates state`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.onEvent(SettingsEvent.SetAnalyticsOptIn(true))
            assertTrue(analyticsPrefs.analyticsOptIn.value)
            viewModel.state.test {
                assertTrue(awaitItem().analyticsOptIn)
            }
            viewModel.onEvent(SettingsEvent.SetAnalyticsOptIn(false))
            assertFalse(analyticsPrefs.analyticsOptIn.value)
            viewModel.state.test {
                assertFalse(awaitItem().analyticsOptIn)
            }
        }
}

private class FakeAnalyticsPreferences : AnalyticsPreferences {
    private val _flow = MutableStateFlow(false)
    override val analyticsOptIn: StateFlow<Boolean> = _flow.asStateFlow()

    override fun setAnalyticsOptIn(value: Boolean) {
        _flow.value = value
    }
}
