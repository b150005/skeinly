package io.github.b150005.skeinly.ui.settings

import app.cash.turbine.test
import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import io.github.b150005.skeinly.data.analytics.EventRingBuffer
import io.github.b150005.skeinly.data.analytics.RecordingAnalyticsTracker
import io.github.b150005.skeinly.data.preferences.AnalyticsPreferences
import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.usecase.CloseRealtimeChannelsUseCase
import io.github.b150005.skeinly.domain.usecase.DeleteAccountUseCase
import io.github.b150005.skeinly.domain.usecase.FakeAuthRepository
import io.github.b150005.skeinly.domain.usecase.ObserveAuthStateUseCase
import io.github.b150005.skeinly.domain.usecase.SignOutUseCase
import io.github.b150005.skeinly.domain.usecase.UpdateEmailUseCase
import io.github.b150005.skeinly.domain.usecase.UpdatePasswordUseCase
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

    private fun createViewModel(eventRingBuffer: EventRingBuffer? = null): SettingsViewModel =
        SettingsViewModel(
            observeAuthState = ObserveAuthStateUseCase(authRepo),
            signOut = SignOutUseCase(authRepo, CloseRealtimeChannelsUseCase(null, null, null)),
            deleteAccount = DeleteAccountUseCase(authRepo, CloseRealtimeChannelsUseCase(null, null, null)),
            updatePassword = UpdatePasswordUseCase(authRepo),
            updateEmail = UpdateEmailUseCase(authRepo),
            analyticsPreferences = analyticsPrefs,
            eventRingBuffer = eventRingBuffer,
        )

    @Test
    fun `initial state loads email from auth state`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))
            val viewModel = createViewModel()
            viewModel.state.test {
                val state = awaitItem()
                assertEquals("test@example.com", state.email)
                assertTrue(state.isSignedIn)
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
                assertFalse(state.isSignedIn)
                assertFalse(state.isLoading)
            }
        }

    /**
     * B3 (Phase 39.1): regression guard for "Settings shows Sign Out /
     * Delete Account while signed out". The screen gates the Account +
     * Danger zone sections on `state.isSignedIn`, so the ViewModel must
     * report `isSignedIn = false` for the unauthenticated state.
     */
    @Test
    fun `unauthenticated state has isSignedIn false B3`() =
        runTest {
            authRepo.setAuthState(AuthState.Unauthenticated)
            val viewModel = createViewModel()
            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isSignedIn, "Unauthenticated must not expose Sign Out UI")
                assertNull(state.email)
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

    /**
     * Phase 39.4 (ADR-015 §6): toggling diagnostic-data sharing OFF
     * MUST drop any event trail accumulated under the prior opt-in
     * window, otherwise a follow-up bug report would attach events
     * captured before the user revoked consent.
     *
     * Uses [UnconfinedTestDispatcher] so the buffer collector attaches
     * synchronously to the SharedFlow before the test calls
     * `tracker.track()` — see [EventRingBufferTest.runUnconfined] for
     * the same pattern.
     */
    @Test
    fun `SetAnalyticsOptIn false clears the event ring buffer`() =
        runTest(UnconfinedTestDispatcher()) {
            val tracker = RecordingAnalyticsTracker()
            val buffer = EventRingBuffer(tracker = tracker)
            buffer.start(backgroundScope)
            tracker.track(AnalyticsEvent.ProjectCreated)
            tracker.track(AnalyticsEvent.RowIncremented)
            assertEquals(2, buffer.snapshot().size)

            // Wire the buffer into the ViewModel and toggle off.
            val viewModel = createViewModel(eventRingBuffer = buffer)
            viewModel.onEvent(SettingsEvent.SetAnalyticsOptIn(false))

            // Buffer drained on toggle-off.
            assertEquals(emptyList(), buffer.snapshot())
            // Preference also flipped through to the persisted store.
            assertFalse(analyticsPrefs.analyticsOptIn.value)
        }

    @Test
    fun `SetAnalyticsOptIn true does NOT clear the event ring buffer`() =
        runTest(UnconfinedTestDispatcher()) {
            // Toggling ON must NOT clear the buffer — there's nothing
            // sensitive to drop, and clearing would create an unintended
            // gap in the event trail of a user who toggles ON-OFF-ON.
            val tracker = RecordingAnalyticsTracker()
            val buffer = EventRingBuffer(tracker = tracker)
            buffer.start(backgroundScope)
            tracker.track(AnalyticsEvent.ProjectCreated)
            assertEquals(1, buffer.snapshot().size)

            val viewModel = createViewModel(eventRingBuffer = buffer)
            viewModel.onEvent(SettingsEvent.SetAnalyticsOptIn(true))

            assertEquals(1, buffer.snapshot().size, "buffer must be untouched on toggle-on")
        }
}

private class FakeAnalyticsPreferences : AnalyticsPreferences {
    private val _flow = MutableStateFlow(false)
    override val analyticsOptIn: StateFlow<Boolean> = _flow.asStateFlow()

    override fun setAnalyticsOptIn(value: Boolean) {
        _flow.value = value
    }
}
