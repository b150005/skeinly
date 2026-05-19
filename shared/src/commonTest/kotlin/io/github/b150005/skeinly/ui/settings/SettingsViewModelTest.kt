package io.github.b150005.skeinly.ui.settings

import app.cash.turbine.test
import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import io.github.b150005.skeinly.data.analytics.ClickActionId
import io.github.b150005.skeinly.data.analytics.EventRingBuffer
import io.github.b150005.skeinly.data.analytics.RecordingAnalyticsTracker
import io.github.b150005.skeinly.data.analytics.Screen
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

    private fun createViewModel(
        eventRingBuffer: EventRingBuffer? = null,
        analyticsTracker: RecordingAnalyticsTracker? = null,
    ): SettingsViewModel =
        SettingsViewModel(
            observeAuthState = ObserveAuthStateUseCase(authRepo),
            signOut = SignOutUseCase(authRepo, CloseRealtimeChannelsUseCase(null, null, null)),
            deleteAccount = DeleteAccountUseCase(authRepo, CloseRealtimeChannelsUseCase(null, null, null)),
            updatePassword = UpdatePasswordUseCase(authRepo),
            updateEmail = UpdateEmailUseCase(authRepo),
            analyticsPreferences = analyticsPrefs,
            eventRingBuffer = eventRingBuffer,
            analyticsTracker = analyticsTracker,
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

    /**
     * Phase 41.3b (ADR-016 §5.1) — SubscribeToProTapped fires the
     * `ClickAction(SubscribeToPro, Settings)` analytics event so a future
     * regression where the entry tap fires but the paywall fails to
     * surface is observable in PostHog.
     */
    @Test
    fun `SubscribeToProTapped emits ClickAction analytics event`() =
        runTest {
            val tracker = RecordingAnalyticsTracker()
            val viewModel = createViewModel(analyticsTracker = tracker)
            viewModel.onEvent(SettingsEvent.SubscribeToProTapped)
            val click =
                tracker.captured.firstOrNull {
                    it is AnalyticsEvent.ClickAction && it.action == ClickActionId.SubscribeToPro
                }
            assertNotNull(click)
            assertEquals(Screen.Settings, (click as AnalyticsEvent.ClickAction).screen)
        }

    @Test
    fun `SubscribeToProTapped is no-op when analytics tracker is null`() =
        runTest {
            // Default-null analyticsTracker preserves existing test compat.
            val viewModel = createViewModel(analyticsTracker = null)
            // Just verify no exception thrown.
            viewModel.onEvent(SettingsEvent.SubscribeToProTapped)
        }

    /**
     * Phase 40 GA opening (Y1) — `SendFeedbackTapped` is dispatched from the
     * "Send Feedback" row that was promoted out of the Beta section into the
     * About / Legal section. The handler must NOT mutate state and MUST NOT
     * throw, regardless of whether an analytics tracker is wired. The first
     * assertion catches a future regression where someone bolts analytics or
     * a `_state.update {}` onto the branch without revisiting the row's GA
     * contract (which is: the click is a navigation intent, not a state
     * change).
     */
    @Test
    fun `SendFeedbackTapped does not mutate state`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))
            val viewModel = createViewModel()
            viewModel.state.test {
                val before = awaitItem()
                viewModel.onEvent(SettingsEvent.SendFeedbackTapped)
                expectNoEvents()
                assertEquals(before, viewModel.state.value)
            }
        }

    @Test
    fun `SendFeedbackTapped is safe when analytics tracker is null`() =
        runTest {
            // Default-null analyticsTracker mirrors the SubscribeToProTapped
            // null-safety guard; the handler MUST be callable in production
            // builds that route analytics elsewhere as well as in tests that
            // skip wiring a tracker.
            val viewModel = createViewModel(analyticsTracker = null)
            viewModel.onEvent(SettingsEvent.SendFeedbackTapped)
        }

    @Test
    fun `SendFeedbackTapped does not emit any analytics event`() =
        runTest {
            // Phase 40 GA opening (Y1) — keeps the contract that engagement
            // analytics on bug-report intent belong to BugReportPreviewScreen,
            // not the Settings click. If a future change wants to emit
            // `ClickAction(SendFeedback, Settings)` here, this guard must be
            // updated in the same commit that adds `ClickActionId.SendFeedback`.
            val tracker = RecordingAnalyticsTracker()
            val viewModel = createViewModel(analyticsTracker = tracker)
            viewModel.onEvent(SettingsEvent.SendFeedbackTapped)
            assertTrue(
                tracker.captured.isEmpty(),
                "SendFeedbackTapped must not emit analytics today",
            )
        }

    // Phase 26.5 (ADR-022 §6.4) — MFA disable path coverage.

    @Test
    fun `DisableMfaConfirmed without enrolled factor is no-op`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "u@example.com"))
            val viewModel = createMfaAwareViewModel()
            viewModel.onEvent(SettingsEvent.DisableMfaConfirmed)
            assertEquals(0, authRepo.disableMfaCallCount)
        }

    @Test
    fun `DisableMfaConfirmed targets the active factor ID`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "u@example.com"))
            authRepo.setMfaStatus(
                io.github.b150005.skeinly.domain.model.MfaEnrollmentStatus
                    .Enrolled("active-1"),
            )
            val viewModel = createMfaAwareViewModel()
            viewModel.onEvent(SettingsEvent.DisableMfaConfirmed)
            assertEquals(1, authRepo.disableMfaCallCount)
            assertEquals("active-1", authRepo.lastDisabledFactorId)
        }

    @Test
    fun `DisableMfaConfirmed failure surfaces Generic error`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "u@example.com"))
            authRepo.setMfaStatus(
                io.github.b150005.skeinly.domain.model.MfaEnrollmentStatus
                    .Enrolled("active-1"),
            )
            authRepo.disableMfaError = IllegalStateException("network")
            val viewModel = createMfaAwareViewModel()
            viewModel.onEvent(SettingsEvent.DisableMfaConfirmed)
            assertNotNull(viewModel.state.value.error)
        }

    // Phase 26.6 (ADR-022 §6.5) — biometric sensitive-action gate on disable-MFA.

    @Test
    fun `DisableMfaConfirmed biometric Cancelled aborts without calling disable`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "u@example.com"))
            authRepo.setMfaStatus(
                io.github.b150005.skeinly.domain.model.MfaEnrollmentStatus
                    .Enrolled("active-1"),
            )
            val viewModel =
                createMfaAwareViewModel(
                    requireBiometricForMfaDisable = {
                        io.github.b150005.skeinly.biometric.BiometricResult.Cancelled
                    },
                )
            viewModel.onEvent(SettingsEvent.DisableMfaConfirmed)
            assertEquals(0, authRepo.disableMfaCallCount, "Cancelled biometric must skip the disable RPC")
            assertFalse(viewModel.state.value.isDisablingMfa)
            assertNull(viewModel.state.value.error, "Cancelled is user intent — no error UI")
        }

    @Test
    fun `DisableMfaConfirmed biometric Failed aborts and surfaces error`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "u@example.com"))
            authRepo.setMfaStatus(
                io.github.b150005.skeinly.domain.model.MfaEnrollmentStatus
                    .Enrolled("active-1"),
            )
            val viewModel =
                createMfaAwareViewModel(
                    requireBiometricForMfaDisable = {
                        io.github.b150005.skeinly.biometric.BiometricResult.Failed
                    },
                )
            viewModel.onEvent(SettingsEvent.DisableMfaConfirmed)
            assertEquals(0, authRepo.disableMfaCallCount)
            assertNotNull(viewModel.state.value.error)
        }

    @Test
    fun `DisableMfaConfirmed biometric Success falls through to disable RPC`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "u@example.com"))
            authRepo.setMfaStatus(
                io.github.b150005.skeinly.domain.model.MfaEnrollmentStatus
                    .Enrolled("active-1"),
            )
            val viewModel =
                createMfaAwareViewModel(
                    requireBiometricForMfaDisable = {
                        io.github.b150005.skeinly.biometric.BiometricResult.Success
                    },
                )
            viewModel.onEvent(SettingsEvent.DisableMfaConfirmed)
            assertEquals(1, authRepo.disableMfaCallCount)
            assertEquals("active-1", authRepo.lastDisabledFactorId)
        }

    // Phase 26.6 (ADR-022 §6.5) — biometric gate on the delete-account path.
    // The gate fires BEFORE the destructive RPC; Cancelled returns silently
    // (no error toast), Failed surfaces ErrorMessage.Generic.

    @Test
    fun `DeleteAccount biometric Cancelled aborts without firing the RPC`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "u@example.com"))
            val viewModel =
                createMfaAwareViewModel(
                    requireBiometricForAccountDelete = {
                        io.github.b150005.skeinly.biometric.BiometricResult.Cancelled
                    },
                )
            viewModel.onEvent(SettingsEvent.DeleteAccountConfirmed)
            // Auth state unchanged → RPC was never called.
            assertEquals("user-1", authRepo.getCurrentUserId())
            assertFalse(viewModel.state.value.isDeletingAccount)
            assertNull(viewModel.state.value.error, "Cancelled is user intent — no error UI")
        }

    @Test
    fun `DeleteAccount biometric Failed surfaces generic error without RPC`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "u@example.com"))
            val viewModel =
                createMfaAwareViewModel(
                    requireBiometricForAccountDelete = {
                        io.github.b150005.skeinly.biometric.BiometricResult.Failed
                    },
                )
            viewModel.onEvent(SettingsEvent.DeleteAccountConfirmed)
            assertEquals("user-1", authRepo.getCurrentUserId())
            assertNotNull(viewModel.state.value.error)
        }

    @Test
    fun `DeleteAccount biometric Success falls through to delete RPC`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-1", "u@example.com"))
            val viewModel =
                createMfaAwareViewModel(
                    requireBiometricForAccountDelete = {
                        io.github.b150005.skeinly.biometric.BiometricResult.Success
                    },
                )
            viewModel.onEvent(SettingsEvent.DeleteAccountConfirmed)
            // Auth state cleared by the cascading delete → null after the RPC.
            assertNull(authRepo.getCurrentUserId())
        }

    private fun createMfaAwareViewModel(
        requireBiometricForMfaDisable: suspend () -> io.github.b150005.skeinly.biometric.BiometricResult = {
            io.github.b150005.skeinly.biometric.BiometricResult.Success
        },
        requireBiometricForAccountDelete: suspend () -> io.github.b150005.skeinly.biometric.BiometricResult = {
            io.github.b150005.skeinly.biometric.BiometricResult.Success
        },
    ): SettingsViewModel =
        SettingsViewModel(
            observeAuthState = ObserveAuthStateUseCase(authRepo),
            signOut = SignOutUseCase(authRepo, CloseRealtimeChannelsUseCase(null, null, null)),
            deleteAccount = DeleteAccountUseCase(authRepo, CloseRealtimeChannelsUseCase(null, null, null)),
            updatePassword = UpdatePasswordUseCase(authRepo),
            updateEmail = UpdateEmailUseCase(authRepo),
            analyticsPreferences = analyticsPrefs,
            observeMfaStatusFlow = { authRepo.observeMfaStatus() },
            disableMfa = authRepo::disableMfa,
            requireBiometricForMfaDisable = requireBiometricForMfaDisable,
            requireBiometricForAccountDelete = requireBiometricForAccountDelete,
        )

    // ========================================================
    // Phase 26.6 (ADR-022 §6.6) — Settings → Account identity list
    // ========================================================

    @Test
    fun `Authenticated emission populates linkedIdentities from AuthRepository`() =
        runTest {
            authRepo.linkedIdentitiesResult =
                listOf(
                    io.github.b150005.skeinly.domain.model.LinkedIdentity(
                        provider = io.github.b150005.skeinly.domain.model.AuthProviderKind.Google,
                        email = "alice@example.com",
                    ),
                )
            authRepo.setAuthState(AuthState.Authenticated("user-1", "alice@example.com"))
            val viewModel = createIdentityAwareViewModel()
            // UnconfinedTestDispatcher runs the init flow eagerly so the
            // post-Authenticated state.value already reflects identities.
            val current = viewModel.state.value
            assertTrue(current.isSignedIn)
            assertEquals(1, current.linkedIdentities.size)
            assertEquals(
                io.github.b150005.skeinly.domain.model.AuthProviderKind.Google,
                current.linkedIdentities[0].provider,
            )
            assertEquals(1, authRepo.getLinkedIdentitiesCallCount)
        }

    @Test
    fun `Unauthenticated emission clears linkedIdentities`() =
        runTest {
            authRepo.linkedIdentitiesResult =
                listOf(
                    io.github.b150005.skeinly.domain.model.LinkedIdentity(
                        provider = io.github.b150005.skeinly.domain.model.AuthProviderKind.Apple,
                        email = "abc123@privaterelay.appleid.com",
                    ),
                )
            authRepo.setAuthState(AuthState.Authenticated("user-1", "abc123@privaterelay.appleid.com"))
            val viewModel = createIdentityAwareViewModel()
            assertEquals(1, viewModel.state.value.linkedIdentities.size)
            authRepo.setAuthState(AuthState.Unauthenticated)
            assertFalse(viewModel.state.value.isSignedIn)
            assertEquals(emptyList(), viewModel.state.value.linkedIdentities)
        }

    @Test
    fun `getLinkedIdentities throw is swallowed and state stays signed-in`() =
        runTest {
            authRepo.linkedIdentitiesError = RuntimeException("network down")
            authRepo.setAuthState(AuthState.Authenticated("user-1", "user@example.com"))
            val viewModel = createIdentityAwareViewModel()
            // The throw was caught — isSignedIn still flips, identity list
            // stays empty until a future refresh succeeds.
            assertTrue(viewModel.state.value.isSignedIn)
            assertEquals(emptyList(), viewModel.state.value.linkedIdentities)
        }

    @Test
    fun `Apple relay identity exposes isAppleRelay = true via the model invariant`() =
        runTest {
            val relay =
                io.github.b150005.skeinly.domain.model.LinkedIdentity(
                    provider = io.github.b150005.skeinly.domain.model.AuthProviderKind.Apple,
                    email = "abc123@privaterelay.appleid.com",
                )
            assertTrue(relay.isAppleRelay)
            val regular =
                io.github.b150005.skeinly.domain.model.LinkedIdentity(
                    provider = io.github.b150005.skeinly.domain.model.AuthProviderKind.Apple,
                    email = "alice@example.com",
                )
            assertFalse(regular.isAppleRelay)
            val google =
                io.github.b150005.skeinly.domain.model.LinkedIdentity(
                    provider = io.github.b150005.skeinly.domain.model.AuthProviderKind.Google,
                    email = "alice@privaterelay.appleid.com",
                )
            // Non-Apple providers never report isAppleRelay even if the email
            // host happens to match — the discriminator is provider-bound.
            assertFalse(google.isAppleRelay)
        }

    @Test
    fun `Multiple linked identities preserve insertion order from the repository`() =
        runTest {
            authRepo.linkedIdentitiesResult =
                listOf(
                    io.github.b150005.skeinly.domain.model.LinkedIdentity(
                        provider = io.github.b150005.skeinly.domain.model.AuthProviderKind.Email,
                        email = "alice@example.com",
                    ),
                    io.github.b150005.skeinly.domain.model.LinkedIdentity(
                        provider = io.github.b150005.skeinly.domain.model.AuthProviderKind.Google,
                        email = "alice@example.com",
                    ),
                )
            authRepo.setAuthState(AuthState.Authenticated("user-1", "alice@example.com"))
            val viewModel = createIdentityAwareViewModel()
            val current = viewModel.state.value
            assertEquals(2, current.linkedIdentities.size)
            assertEquals(
                io.github.b150005.skeinly.domain.model.AuthProviderKind.Email,
                current.linkedIdentities[0].provider,
            )
            assertEquals(
                io.github.b150005.skeinly.domain.model.AuthProviderKind.Google,
                current.linkedIdentities[1].provider,
            )
        }

    @Test
    fun `Loading state surfaces empty linkedIdentities until first Authenticated`() =
        runTest {
            authRepo.linkedIdentitiesResult =
                listOf(
                    io.github.b150005.skeinly.domain.model.LinkedIdentity(
                        provider = io.github.b150005.skeinly.domain.model.AuthProviderKind.Email,
                        email = "alice@example.com",
                    ),
                )
            val viewModel = createIdentityAwareViewModel()
            // Default state without flipping authState is Loading/Unauthenticated.
            val firstState = viewModel.state.value
            assertEquals(emptyList(), firstState.linkedIdentities)
            assertFalse(firstState.isSignedIn)
        }

    private fun createIdentityAwareViewModel(): SettingsViewModel =
        SettingsViewModel(
            observeAuthState = ObserveAuthStateUseCase(authRepo),
            signOut = SignOutUseCase(authRepo, CloseRealtimeChannelsUseCase(null, null, null)),
            deleteAccount = DeleteAccountUseCase(authRepo, CloseRealtimeChannelsUseCase(null, null, null)),
            updatePassword = UpdatePasswordUseCase(authRepo),
            updateEmail = UpdateEmailUseCase(authRepo),
            analyticsPreferences = analyticsPrefs,
            loadLinkedIdentities = { authRepo.getLinkedIdentities() },
        )
}

private class FakeAnalyticsPreferences : AnalyticsPreferences {
    private val _flow = MutableStateFlow(false)
    override val analyticsOptIn: StateFlow<Boolean> = _flow.asStateFlow()

    override fun setAnalyticsOptIn(value: Boolean) {
        _flow.value = value
    }
}
