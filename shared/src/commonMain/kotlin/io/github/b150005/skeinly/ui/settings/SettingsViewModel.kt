package io.github.b150005.skeinly.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.biometric.BiometricResult
import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import io.github.b150005.skeinly.data.analytics.AnalyticsTracker
import io.github.b150005.skeinly.data.analytics.ClickActionId
import io.github.b150005.skeinly.data.analytics.EventRingBuffer
import io.github.b150005.skeinly.data.analytics.Screen
import io.github.b150005.skeinly.data.preferences.AnalyticsPreferences
import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.model.LinkedIdentity
import io.github.b150005.skeinly.domain.model.MfaEnrollmentStatus
import io.github.b150005.skeinly.domain.usecase.DeleteAccountUseCase
import io.github.b150005.skeinly.domain.usecase.ErrorMessage
import io.github.b150005.skeinly.domain.usecase.ObserveAuthStateUseCase
import io.github.b150005.skeinly.domain.usecase.SignOutUseCase
import io.github.b150005.skeinly.domain.usecase.UpdateEmailUseCase
import io.github.b150005.skeinly.domain.usecase.UpdatePasswordUseCase
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import io.github.b150005.skeinly.domain.usecase.toErrorMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsState(
    val email: String? = null,
    /**
     * B3 (Phase 39.1): explicit auth gate — `true` only when the
     * `ObserveAuthStateUseCase` flow emits [AuthState.Authenticated]. UI
     * uses this to hide Account / Sign Out / Change Password / Change
     * Email / Delete Account sections when the user has never signed in
     * or has signed out. Using `email != null` as a proxy is fragile
     * because some auth providers can return Authenticated with a null
     * email; the explicit boolean is unambiguous.
     */
    val isSignedIn: Boolean = false,
    val isLoading: Boolean = true,
    val isDeletingAccount: Boolean = false,
    val isChangingPassword: Boolean = false,
    val isChangingEmail: Boolean = false,
    val pendingChangePasswordDialog: Boolean = false,
    val pendingChangeEmailDialog: Boolean = false,
    val analyticsOptIn: Boolean = false,
    val error: ErrorMessage? = null,
    /**
     * Phase 26.5 (ADR-022 §6.4) — coarse MFA enrollment status used by
     * the Settings → Security row. Default [MfaEnrollmentStatus.NotEnrolled]
     * is correct for the unconfigured-supabase (local-only dev) path
     * and the signed-out path; production wiring re-emits as the
     * underlying status flow advances.
     */
    val mfaStatus: MfaEnrollmentStatus = MfaEnrollmentStatus.NotEnrolled,
    val isDisablingMfa: Boolean = false,
    /**
     * Phase 26.6 (ADR-022 §6.6) — list of identities attached to the
     * current Supabase user, surfacing the "Signed in via X" row
     * + (when multiple) the linked-accounts list. Empty list maps to
     * "hide both rows" — defensive default for the unconfigured /
     * signed-out paths.
     */
    val linkedIdentities: List<LinkedIdentity> = emptyList(),
)

sealed interface SettingsEvent {
    data object SignOut : SettingsEvent

    data object DeleteAccountConfirmed : SettingsEvent

    data object RequestChangePassword : SettingsEvent

    data object DismissChangePassword : SettingsEvent

    data class ConfirmChangePassword(
        val newPassword: String,
    ) : SettingsEvent

    data object RequestChangeEmail : SettingsEvent

    data object DismissChangeEmail : SettingsEvent

    data class ConfirmChangeEmail(
        val newEmail: String,
    ) : SettingsEvent

    data class SetAnalyticsOptIn(
        val value: Boolean,
    ) : SettingsEvent

    /**
     * Phase 41.3b (ADR-016 §5.1) — fired when the user taps the "Subscribe
     * to Pro" entry in the Pro section. Captures the engagement intent
     * BEFORE the paywall sheet opens, distinct from `PaywallOpened(trigger
     * = Settings)` which captures the outcome of the tap (sheet
     * successfully surfaced). Click-through analytics use the pair to
     * spot a future regression where the tap fires but the sheet fails
     * to render.
     */
    data object SubscribeToProTapped : SettingsEvent

    /**
     * Phase 26.5 (ADR-022 §6.4) — fires the disable-MFA RPC (auth.mfa.unenroll)
     * on the currently-enrolled factor. The Settings row pre-confirms via
     * an AlertDialog at the UI layer; this event executes the action.
     * Alpha scope does NOT gate on a fresh TOTP re-prompt — biometric +
     * sensitive-action gating lands in 26.6.
     */
    data object DisableMfaConfirmed : SettingsEvent

    data object ClearError : SettingsEvent
}

/**
 * One-shot toast events surfaced to the screen via a [Channel]. Captured
 * with sealed type so future toast types (e.g., "email_confirmed") can
 * extend without changing the ViewModel API surface.
 */
sealed interface SettingsToastEvent {
    data object PasswordChanged : SettingsToastEvent

    data object EmailChangePending : SettingsToastEvent
}

class SettingsViewModel(
    private val observeAuthState: ObserveAuthStateUseCase,
    private val signOut: SignOutUseCase,
    private val deleteAccount: DeleteAccountUseCase,
    private val updatePassword: UpdatePasswordUseCase,
    private val updateEmail: UpdateEmailUseCase,
    private val analyticsPreferences: AnalyticsPreferences,
    // Phase 39.4 (ADR-015 §6) — when the user toggles diagnostic-data
    // sharing OFF we MUST drop any event trail accumulated under the
    // prior opt-in window so a follow-up bug report after opt-out does
    // not leak the pre-revocation events. Nullable + default null keeps
    // existing test call-sites valid; production wiring always supplies
    // non-null via [ViewModelModule].
    private val eventRingBuffer: EventRingBuffer? = null,
    // Phase 41.3b (ADR-016 §5.1) — emit `ClickAction(SubscribeToPro,
    // Settings)` when the user taps the Pro entry. Nullable + default null
    // preserves existing test compat; production wiring always supplies
    // the tracker via [ViewModelModule].
    private val analyticsTracker: AnalyticsTracker? = null,
    // Phase 26.5 (ADR-022 §6.4) — MFA observation + disable. Defaulted
    // to constant-NotEnrolled + no-op for tests that don't care; production
    // wiring binds to `authRepository::observeMfaStatus` /
    // `authRepository::disableMfa`. The factor ID is sourced from the
    // observed status row at disable time (no parameter).
    private val observeMfaStatusFlow: () -> Flow<MfaEnrollmentStatus> = {
        kotlinx.coroutines.flow.flowOf(MfaEnrollmentStatus.NotEnrolled)
    },
    private val disableMfa: suspend (factorId: String) -> Unit = { /* no-op */ },
    // Phase 26.6 (ADR-022 §6.5) — biometric sensitive-action gate
    // fired immediately before the disable-MFA RPC. Lambda seam over
    // [io.github.b150005.skeinly.biometric.BiometricGuardian.requireForAction]
    // so the VM stays testable without standing up the biometric stack.
    // Default returns Success — tests that don't care, and dev builds
    // wired without the Guardian, structurally skip the gate.
    private val requireBiometricForMfaDisable: suspend () -> BiometricResult = {
        BiometricResult.Success
    },
    // Phase 26.6 (ADR-022 §6.5) — biometric sensitive-action gate
    // fired immediately before the cascading account-deletion RPC.
    // Same lambda-seam shape + same Cancelled-vs-Failed UI semantics
    // as [requireBiometricForMfaDisable] above. The gate lives at the
    // VM (not inside `DeleteAccountUseCase`) so user-cancel of the
    // biometric prompt produces a silent UI reset rather than a
    // "Permission denied" error toast.
    private val requireBiometricForAccountDelete: suspend () -> BiometricResult = {
        BiometricResult.Success
    },
    // Phase 26.6 (ADR-022 §6.6) — populates the Settings → Account
    // identity row. Default returns an empty list so tests that don't
    // wire this still pass (row simply doesn't render). Production
    // wiring binds to `authRepository::getLinkedIdentities` — fires
    // once per Authenticated transition (see [loadSettings]).
    private val loadLinkedIdentities: suspend () -> List<LinkedIdentity> = { emptyList() },
) : ViewModel() {
    private val _state = MutableStateFlow(SettingsState())
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _accountDeletedChannel = Channel<Unit>(Channel.BUFFERED)
    val accountDeleted: Flow<Unit> = _accountDeletedChannel.receiveAsFlow()

    private val _toastChannel = Channel<SettingsToastEvent>(Channel.BUFFERED)
    val toastEvents: Flow<SettingsToastEvent> = _toastChannel.receiveAsFlow()

    init {
        loadSettings()
        observeAnalyticsOptIn()
        observeMfaStatusInternal()
    }

    private fun observeMfaStatusInternal() {
        viewModelScope.launch {
            observeMfaStatusFlow().collect { status ->
                _state.update { it.copy(mfaStatus = status) }
            }
        }
    }

    private fun observeAnalyticsOptIn() {
        viewModelScope.launch {
            analyticsPreferences.analyticsOptIn.collect { value ->
                _state.update { it.copy(analyticsOptIn = value) }
            }
        }
    }

    fun onEvent(event: SettingsEvent) {
        when (event) {
            SettingsEvent.SignOut -> performSignOut()
            SettingsEvent.DeleteAccountConfirmed -> performDeleteAccount()
            SettingsEvent.RequestChangePassword ->
                _state.update { it.copy(pendingChangePasswordDialog = true) }
            SettingsEvent.DismissChangePassword ->
                _state.update { it.copy(pendingChangePasswordDialog = false) }
            is SettingsEvent.ConfirmChangePassword -> performChangePassword(event.newPassword)
            SettingsEvent.RequestChangeEmail ->
                _state.update { it.copy(pendingChangeEmailDialog = true) }
            SettingsEvent.DismissChangeEmail ->
                _state.update { it.copy(pendingChangeEmailDialog = false) }
            is SettingsEvent.ConfirmChangeEmail -> performChangeEmail(event.newEmail)
            is SettingsEvent.SetAnalyticsOptIn -> {
                // Phase 39.4 (ADR-015 §6): clear the in-memory event
                // trail BEFORE flipping the persisted preference OFF —
                // ordering matters because a concurrent bug-report
                // submission running on the snapshot side reads the
                // buffer through the same Mutex. Clearing first ensures
                // no read-after-revoke window. Idempotent on already-empty.
                if (!event.value) {
                    viewModelScope.launch {
                        eventRingBuffer?.clear()
                    }
                }
                analyticsPreferences.setAnalyticsOptIn(event.value)
            }
            SettingsEvent.SubscribeToProTapped ->
                analyticsTracker?.track(
                    AnalyticsEvent.ClickAction(ClickActionId.SubscribeToPro, Screen.Settings),
                )
            SettingsEvent.DisableMfaConfirmed -> performDisableMfa()
            SettingsEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun performDisableMfa() {
        // Phase 26.6 (ADR-022 §6.5) — re-entry guard. Same rationale as
        // performDeleteAccount: the biometric prompt suspends the
        // coroutine for several seconds with `isDisablingMfa` already
        // set to true, but a second tap before the first request lands
        // could still race the `factorId` resolution if the state
        // mutates between launch and gate. Defending at the entry
        // point is the simplest invariant.
        if (_state.value.isDisablingMfa) return
        val factorId =
            when (val status = _state.value.mfaStatus) {
                is MfaEnrollmentStatus.Enrolled -> status.factorId
                is MfaEnrollmentStatus.EnrolledUnverified -> status.factorId
                MfaEnrollmentStatus.NotEnrolled -> return
            }
        viewModelScope.launch {
            _state.update { it.copy(isDisablingMfa = true, error = null) }
            // Phase 26.6 (ADR-022 §6.5) — biometric gate. On Cancelled
            // / Failed / Unavailable-via-error we abort without firing
            // the disable RPC. Success (gate passed OR not enforceable
            // — see BiometricGuardian.requireForAction) falls through
            // to the RPC. We keep `isDisablingMfa = true` across the
            // gate so the UI stays in the busy state during the OS
            // prompt and there's no flicker on success.
            val gate = requireBiometricForMfaDisable()
            when (gate) {
                BiometricResult.Success -> Unit
                BiometricResult.Cancelled -> {
                    _state.update { it.copy(isDisablingMfa = false) }
                    return@launch
                }
                BiometricResult.Failed,
                BiometricResult.Unavailable,
                -> {
                    _state.update {
                        it.copy(isDisablingMfa = false, error = ErrorMessage.Generic)
                    }
                    return@launch
                }
            }
            try {
                disableMfa(factorId)
                _state.update { it.copy(isDisablingMfa = false) }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Throwable) {
                _state.update {
                    it.copy(
                        isDisablingMfa = false,
                        error = ErrorMessage.Generic,
                    )
                }
            }
        }
    }

    private fun loadSettings() {
        viewModelScope.launch {
            observeAuthState()
                .collect { authState ->
                    when (authState) {
                        is AuthState.Authenticated -> {
                            _state.update {
                                it.copy(
                                    email = authState.email,
                                    isSignedIn = true,
                                    isLoading = false,
                                )
                            }
                            // Phase 26.6 — refresh linked-identities on
                            // every Authenticated transition (covers both
                            // first sign-in + linkIdentity merge success).
                            // Defensive try-catch: a failure here MUST
                            // NOT block the signed-in surface from
                            // rendering — the Account identity row just
                            // stays empty until the next refresh.
                            val identities =
                                try {
                                    loadLinkedIdentities()
                                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                                    throw e
                                } catch (_: Throwable) {
                                    emptyList()
                                }
                            _state.update { it.copy(linkedIdentities = identities) }
                        }
                        else ->
                            _state.update {
                                it.copy(
                                    email = null,
                                    isSignedIn = false,
                                    isLoading = false,
                                    linkedIdentities = emptyList(),
                                )
                            }
                    }
                }
        }
    }

    private fun performSignOut() {
        viewModelScope.launch {
            when (val result = signOut()) {
                is UseCaseResult.Success -> { /* Auth state observer handles navigation */ }
                is UseCaseResult.Failure ->
                    _state.update { it.copy(error = result.error.toErrorMessage()) }
            }
        }
    }

    private fun performDeleteAccount() {
        // Phase 26.6 (ADR-022 §6.5) — re-entry guard. The biometric
        // prompt suspends the coroutine for several seconds during
        // which `isDeletingAccount` is still false (we deliberately
        // don't flip it until after the gate to avoid spinner-flash
        // behind the OS dialog). Without this guard, a user who
        // dismisses the prompt + navigates away + re-taps delete +
        // re-confirms would queue a second `deleteAccount()` RPC.
        // Mirrors the MfaEnrollmentViewModel.Start idempotency pattern.
        if (_state.value.isDeletingAccount) return
        viewModelScope.launch {
            // Biometric gate BEFORE flipping `isDeletingAccount = true`
            // so the spinner does not flash behind the OS biometric
            // prompt. On Cancelled the UI returns to the pre-tap state
            // without any error UI; on Failed/Unavailable surface a
            // generic error (the in-app destructive-confirm dialog has
            // already been dismissed by the caller, so a silent return
            // would feel like a no-op).
            when (requireBiometricForAccountDelete()) {
                BiometricResult.Success -> Unit
                BiometricResult.Cancelled -> return@launch
                BiometricResult.Failed,
                BiometricResult.Unavailable,
                -> {
                    _state.update { it.copy(error = ErrorMessage.Generic) }
                    return@launch
                }
            }
            _state.update { it.copy(isDeletingAccount = true) }
            when (val result = deleteAccount()) {
                is UseCaseResult.Success -> {
                    _state.update { it.copy(isDeletingAccount = false) }
                    _accountDeletedChannel.send(Unit)
                }
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(isDeletingAccount = false, error = result.error.toErrorMessage())
                    }
            }
        }
    }

    private fun performChangePassword(newPassword: String) {
        viewModelScope.launch {
            _state.update { it.copy(isChangingPassword = true) }
            when (val result = updatePassword(newPassword)) {
                is UseCaseResult.Success -> {
                    _state.update {
                        it.copy(
                            isChangingPassword = false,
                            pendingChangePasswordDialog = false,
                        )
                    }
                    _toastChannel.send(SettingsToastEvent.PasswordChanged)
                }
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(
                            isChangingPassword = false,
                            error = result.error.toErrorMessage(),
                        )
                    }
            }
        }
    }

    private fun performChangeEmail(newEmail: String) {
        viewModelScope.launch {
            _state.update { it.copy(isChangingEmail = true) }
            when (val result = updateEmail(newEmail)) {
                is UseCaseResult.Success -> {
                    _state.update {
                        it.copy(
                            isChangingEmail = false,
                            pendingChangeEmailDialog = false,
                        )
                    }
                    _toastChannel.send(SettingsToastEvent.EmailChangePending)
                }
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(
                            isChangingEmail = false,
                            error = result.error.toErrorMessage(),
                        )
                    }
            }
        }
    }
}
