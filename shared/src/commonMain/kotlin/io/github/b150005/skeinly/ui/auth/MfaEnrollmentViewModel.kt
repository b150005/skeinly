package io.github.b150005.skeinly.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.domain.model.MfaEnrollment
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 26.5 (ADR-022 §6.4) — drives the TOTP enrollment + verify +
 * recovery-code-display flow under Settings → Security → 2FA.
 *
 * Three-phase state machine:
 *
 *   1. [phase = PairingQr] — user just tapped "Enable 2FA". VM fires
 *      [enrollMfaTotp] which calls Supabase `auth.mfa.enroll` +
 *      registers the recovery code hash. On success the
 *      [MfaEnrollment] envelope is stored on state and the UI renders
 *      the QR + manual-secret pairing screen.
 *   2. [phase = ConfirmCode] — user scanned with their authenticator
 *      app and entered the 6-digit TOTP into a code field. VM fires
 *      [verifyMfaEnrollment] which calls
 *      `auth.mfa.createChallenge + verifyChallenge` to complete
 *      enrollment.
 *   3. [phase = RecoveryCodeDisplay] — verify succeeded; the UI
 *      shows the one-time recovery code with screenshot-recommend
 *      warning. On dismiss the recovery code is dropped from state
 *      (the plaintext is unrecoverable unless the user regenerates
 *      from Settings).
 *
 * Lambda-seam DI: `enrollMfaTotp` + `verifyMfaEnrollment` are
 * suspending lambdas (not the full AuthRepository) so commonTest can
 * inject stubs without pulling the supabase-kt surface. Production
 * wiring binds them to `authRepository::enrollMfaTotp` /
 * `::verifyMfaEnrollment` via the ViewModelModule.
 */
data class MfaEnrollmentUiState(
    val phase: MfaEnrollmentPhase = MfaEnrollmentPhase.PairingQr,
    val enrollment: MfaEnrollment? = null,
    val codeInput: String = "",
    val isSubmitting: Boolean = false,
    val error: MfaEnrollmentError? = null,
    val completed: Boolean = false,
)

enum class MfaEnrollmentPhase {
    PairingQr,
    ConfirmCode,
    RecoveryCodeDisplay,
}

sealed interface MfaEnrollmentError {
    data object EnrollFailed : MfaEnrollmentError

    data object InvalidCode : MfaEnrollmentError

    data object Generic : MfaEnrollmentError
}

sealed interface MfaEnrollmentEvent {
    data object Start : MfaEnrollmentEvent

    data class UpdateCode(
        val code: String,
    ) : MfaEnrollmentEvent

    data object AdvanceToConfirm : MfaEnrollmentEvent

    data object SubmitCode : MfaEnrollmentEvent

    data object DismissRecoveryCode : MfaEnrollmentEvent

    data object ClearError : MfaEnrollmentEvent
}

class MfaEnrollmentViewModel(
    private val enrollMfaTotp: suspend () -> MfaEnrollment,
    private val verifyMfaEnrollment: suspend (factorId: String, code: String) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(MfaEnrollmentUiState())
    val state: StateFlow<MfaEnrollmentUiState> = _state.asStateFlow()

    fun onEvent(event: MfaEnrollmentEvent) {
        when (event) {
            MfaEnrollmentEvent.Start -> start()
            is MfaEnrollmentEvent.UpdateCode ->
                _state.update { it.copy(codeInput = event.code.filter(Char::isDigit).take(6)) }
            MfaEnrollmentEvent.AdvanceToConfirm ->
                _state.update { it.copy(phase = MfaEnrollmentPhase.ConfirmCode) }
            MfaEnrollmentEvent.SubmitCode -> submitCode()
            MfaEnrollmentEvent.DismissRecoveryCode ->
                _state.update { it.copy(completed = true, enrollment = null) }
            MfaEnrollmentEvent.ClearError ->
                _state.update { it.copy(error = null) }
        }
    }

    private fun start() {
        if (_state.value.isSubmitting || _state.value.enrollment != null) return
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            try {
                val enrollment = enrollMfaTotp()
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        enrollment = enrollment,
                        phase = MfaEnrollmentPhase.PairingQr,
                    )
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Throwable) {
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        error = MfaEnrollmentError.EnrollFailed,
                    )
                }
            }
        }
    }

    private fun submitCode() {
        val current = _state.value
        if (current.isSubmitting) return
        val enrollment = current.enrollment ?: return
        val code = current.codeInput
        if (code.length != 6) {
            _state.update { it.copy(error = MfaEnrollmentError.InvalidCode) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            try {
                verifyMfaEnrollment(enrollment.factorId, code)
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        phase = MfaEnrollmentPhase.RecoveryCodeDisplay,
                        codeInput = "",
                    )
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Throwable) {
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        error = MfaEnrollmentError.InvalidCode,
                    )
                }
            }
        }
    }
}
