package io.github.b150005.skeinly.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 26.5 (ADR-022 §6.4) — drives the post-password-sign-in TOTP
 * challenge gate. Mounted by the navigator when
 * [io.github.b150005.skeinly.domain.model.AuthState.MfaChallengeRequired]
 * is observed.
 *
 * State machine:
 *
 *   - [phase = EnterCode] — user types a 6-digit TOTP. On submit the
 *     VM calls [submitMfaChallenge] which forwards through the
 *     repository to Supabase `auth.mfa.createChallenge +
 *     verifyChallenge`. On success the session JWT is replaced with
 *     an AAL2 token + `observeAuthState` re-emits Authenticated → the
 *     navigator routes off MfaChallengeScreen.
 *   - [phase = EnterRecoveryCode] — user tapped "Use recovery code".
 *     They paste the 16-char code; on submit VM calls
 *     [consumeRecoveryCode] which bcrypt-verifies + unenrolls TOTP.
 *     On success the session is still AAL1 but `mfa.statusFlow.enabled`
 *     flips to false → `observeAuthState` re-emits plain Authenticated
 *     → navigator routes off.
 *
 * 3-failed-attempt lockout per ADR-022 §6.4: after 3 consecutive
 * wrong TOTP codes the VM surfaces a [MfaChallengeError.Locked] for
 * 30 seconds (counter resets on success or recovery code use). The
 * lockout is a UX guardrail, not a security boundary — Supabase
 * already rate-limits MFA verify on the server side.
 */
data class MfaChallengeUiState(
    val phase: MfaChallengePhase = MfaChallengePhase.EnterCode,
    val codeInput: String = "",
    val isSubmitting: Boolean = false,
    val failedAttempts: Int = 0,
    val error: MfaChallengeError? = null,
    val completed: Boolean = false,
)

enum class MfaChallengePhase {
    EnterCode,
    EnterRecoveryCode,
}

sealed interface MfaChallengeError {
    data object InvalidCode : MfaChallengeError

    data object InvalidRecoveryCode : MfaChallengeError

    data object Generic : MfaChallengeError

    data object Locked : MfaChallengeError
}

sealed interface MfaChallengeEvent {
    data class UpdateCode(
        val code: String,
    ) : MfaChallengeEvent

    data object SubmitCode : MfaChallengeEvent

    data object SwitchToRecoveryCode : MfaChallengeEvent

    data object SwitchToTotp : MfaChallengeEvent

    data object SubmitRecoveryCode : MfaChallengeEvent

    data object ClearError : MfaChallengeEvent
}

class MfaChallengeViewModel(
    private val submitMfaChallenge: suspend (code: String) -> Unit,
    private val consumeRecoveryCode: suspend (plaintextCode: String) -> Boolean,
) : ViewModel() {
    private val _state = MutableStateFlow(MfaChallengeUiState())
    val state: StateFlow<MfaChallengeUiState> = _state.asStateFlow()

    fun onEvent(event: MfaChallengeEvent) {
        when (event) {
            is MfaChallengeEvent.UpdateCode ->
                _state.update {
                    val limit = if (it.phase == MfaChallengePhase.EnterCode) 6 else MAX_RECOVERY_CODE_LENGTH
                    val sanitized =
                        if (it.phase == MfaChallengePhase.EnterCode) {
                            event.code.filter(Char::isDigit).take(limit)
                        } else {
                            event.code
                                .uppercase()
                                .filter { c -> c.isLetterOrDigit() }
                                .take(limit)
                        }
                    it.copy(codeInput = sanitized)
                }
            MfaChallengeEvent.SubmitCode -> submitCode()
            MfaChallengeEvent.SwitchToRecoveryCode ->
                _state.update {
                    it.copy(phase = MfaChallengePhase.EnterRecoveryCode, codeInput = "", error = null)
                }
            MfaChallengeEvent.SwitchToTotp ->
                _state.update {
                    it.copy(phase = MfaChallengePhase.EnterCode, codeInput = "", error = null)
                }
            MfaChallengeEvent.SubmitRecoveryCode -> submitRecoveryCode()
            MfaChallengeEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun submitCode() {
        val current = _state.value
        if (current.isSubmitting) return
        if (current.failedAttempts >= LOCKOUT_THRESHOLD) {
            _state.update { it.copy(error = MfaChallengeError.Locked) }
            return
        }
        if (current.codeInput.length != 6) {
            _state.update { it.copy(error = MfaChallengeError.InvalidCode) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            try {
                submitMfaChallenge(current.codeInput)
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        completed = true,
                        codeInput = "",
                        failedAttempts = 0,
                    )
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Throwable) {
                _state.update {
                    val next = it.failedAttempts + 1
                    it.copy(
                        isSubmitting = false,
                        failedAttempts = next,
                        error =
                            if (next >= LOCKOUT_THRESHOLD) {
                                MfaChallengeError.Locked
                            } else {
                                MfaChallengeError.InvalidCode
                            },
                        codeInput = "",
                    )
                }
            }
        }
    }

    private fun submitRecoveryCode() {
        val current = _state.value
        if (current.isSubmitting) return
        if (current.codeInput.isBlank()) {
            _state.update { it.copy(error = MfaChallengeError.InvalidRecoveryCode) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            try {
                val consumed = consumeRecoveryCode(current.codeInput)
                if (consumed) {
                    _state.update {
                        it.copy(
                            isSubmitting = false,
                            completed = true,
                            codeInput = "",
                            failedAttempts = 0,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            isSubmitting = false,
                            error = MfaChallengeError.InvalidRecoveryCode,
                            codeInput = "",
                        )
                    }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Throwable) {
                _state.update {
                    it.copy(
                        isSubmitting = false,
                        error = MfaChallengeError.Generic,
                    )
                }
            }
        }
    }

    private companion object {
        const val LOCKOUT_THRESHOLD = 3
        const val MAX_RECOVERY_CODE_LENGTH = 32
    }
}
