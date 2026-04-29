package io.github.b150005.knitnote.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.usecase.ErrorMessage
import io.github.b150005.knitnote.domain.usecase.SendPasswordResetUseCase
import io.github.b150005.knitnote.domain.usecase.UseCaseResult
import io.github.b150005.knitnote.domain.usecase.toErrorMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ForgotPasswordState(
    val email: String = "",
    val isSubmitting: Boolean = false,
    /** True after the reset email request has been accepted by Supabase. */
    val didSubmit: Boolean = false,
    val error: ErrorMessage? = null,
)

sealed interface ForgotPasswordEvent {
    data class UpdateEmail(
        val email: String,
    ) : ForgotPasswordEvent

    data object Submit : ForgotPasswordEvent

    data object ClearError : ForgotPasswordEvent
}

/**
 * Drives the password-reset request flow: user enters their email → we
 * call Supabase's `resetPasswordForEmail`, which sends a one-time
 * reset link to the address (Supabase's default web reset page handles
 * the actual password change).
 *
 * After successful submission, we surface a generic success state
 * REGARDLESS of whether the email exists in the system — this prevents
 * email-enumeration via differential UI feedback. The Supabase API
 * itself does not differentiate; we mirror that opacity at the UI.
 */
class ForgotPasswordViewModel(
    private val sendPasswordReset: SendPasswordResetUseCase,
) : ViewModel() {
    private val _state = MutableStateFlow(ForgotPasswordState())
    val state: StateFlow<ForgotPasswordState> = _state.asStateFlow()

    fun onEvent(event: ForgotPasswordEvent) {
        when (event) {
            is ForgotPasswordEvent.UpdateEmail -> _state.update { it.copy(email = event.email) }
            ForgotPasswordEvent.Submit -> submit()
            ForgotPasswordEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private fun submit() {
        val current = _state.value
        if (current.isSubmitting) return

        viewModelScope.launch {
            _state.update { it.copy(isSubmitting = true, error = null) }
            when (val result = sendPasswordReset(current.email)) {
                is UseCaseResult.Success ->
                    _state.update { it.copy(isSubmitting = false, didSubmit = true) }
                is UseCaseResult.Failure ->
                    _state.update {
                        it.copy(isSubmitting = false, error = result.error.toErrorMessage())
                    }
            }
        }
    }
}
