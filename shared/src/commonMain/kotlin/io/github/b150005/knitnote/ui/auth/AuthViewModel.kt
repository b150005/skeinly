package io.github.b150005.knitnote.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.usecase.ObserveAuthStateUseCase
import io.github.b150005.knitnote.domain.usecase.SignInUseCase
import io.github.b150005.knitnote.domain.usecase.SignUpUseCase
import io.github.b150005.knitnote.domain.usecase.UseCaseResult
import io.github.b150005.knitnote.domain.usecase.toMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val authState: AuthState = AuthState.Loading,
    val email: String = "",
    val password: String = "",
    val isSignUp: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

sealed interface AuthEvent {
    data class UpdateEmail(
        val email: String,
    ) : AuthEvent

    data class UpdatePassword(
        val password: String,
    ) : AuthEvent

    data object ToggleMode : AuthEvent

    data object Submit : AuthEvent

    data object ClearError : AuthEvent
}

private data class FormState(
    val email: String = "",
    val password: String = "",
    val isSignUp: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

class AuthViewModel(
    private val observeAuthState: ObserveAuthStateUseCase,
    private val signIn: SignInUseCase,
    private val signUp: SignUpUseCase,
) : ViewModel() {
    private val form = MutableStateFlow(FormState())

    val state: StateFlow<AuthUiState> =
        combine(observeAuthState(), form) { authState, formState ->
            AuthUiState(
                authState = authState,
                email = formState.email,
                password = formState.password,
                isSignUp = formState.isSignUp,
                isSubmitting = formState.isSubmitting,
                error = formState.error,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = AuthUiState(),
        )

    fun onEvent(event: AuthEvent) {
        when (event) {
            is AuthEvent.UpdateEmail -> form.update { it.copy(email = event.email) }
            is AuthEvent.UpdatePassword -> form.update { it.copy(password = event.password) }
            AuthEvent.ToggleMode -> form.update { it.copy(isSignUp = !it.isSignUp, error = null) }
            AuthEvent.Submit -> submit()
            AuthEvent.ClearError -> form.update { it.copy(error = null) }
        }
    }

    private fun submit() {
        val current = form.value
        if (current.isSubmitting) return

        viewModelScope.launch {
            form.update { it.copy(isSubmitting = true, error = null) }

            val result =
                if (current.isSignUp) {
                    signUp(current.email, current.password)
                } else {
                    signIn(current.email, current.password)
                }

            when (result) {
                is UseCaseResult.Success -> {
                    form.update { it.copy(isSubmitting = false) }
                }
                is UseCaseResult.Failure -> {
                    form.update { it.copy(isSubmitting = false, error = result.error.toMessage()) }
                }
            }
        }
    }
}
