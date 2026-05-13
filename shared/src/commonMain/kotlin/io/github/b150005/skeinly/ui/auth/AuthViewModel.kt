package io.github.b150005.skeinly.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.model.SignUpOutcome
import io.github.b150005.skeinly.domain.usecase.ErrorMessage
import io.github.b150005.skeinly.domain.usecase.ObserveAuthStateUseCase
import io.github.b150005.skeinly.domain.usecase.SignInUseCase
import io.github.b150005.skeinly.domain.usecase.SignUpUseCase
import io.github.b150005.skeinly.domain.usecase.UseCaseResult
import io.github.b150005.skeinly.domain.usecase.toErrorMessage
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
    val error: ErrorMessage? = null,
    /**
     * Non-null while the screen is in the "check your email" state after a
     * sign-up that returned [SignUpOutcome.EmailConfirmationRequired].
     * Carries the email address the confirmation link was sent to so the
     * UI can surface it ("確認メールを X に送信しました"). UI returns to the
     * sign-in form via [AuthEvent.DismissEmailConfirmation].
     */
    val emailConfirmationSentTo: String? = null,
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

    /**
     * Dismisses the "check your email" confirmation view and returns the
     * form to the sign-in mode (`isSignUp = false`, password cleared,
     * `emailConfirmationSentTo = null`). Fires when the user taps the
     * "ログイン画面に戻る" CTA on the post-sign-up screen.
     */
    data object DismissEmailConfirmation : AuthEvent
}

private data class FormState(
    val email: String = "",
    val password: String = "",
    val isSignUp: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: ErrorMessage? = null,
    val emailConfirmationSentTo: String? = null,
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
                emailConfirmationSentTo = formState.emailConfirmationSentTo,
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
            AuthEvent.DismissEmailConfirmation ->
                form.update {
                    it.copy(
                        emailConfirmationSentTo = null,
                        isSignUp = false,
                        password = "",
                    )
                }
        }
    }

    private fun submit() {
        val current = form.value
        if (current.isSubmitting) return

        viewModelScope.launch {
            form.update { it.copy(isSubmitting = true, error = null) }

            if (current.isSignUp) {
                handleSignUp(current.email, current.password)
            } else {
                handleSignIn(current.email, current.password)
            }
        }
    }

    private suspend fun handleSignUp(
        email: String,
        password: String,
    ) {
        when (val result = signUp(email, password)) {
            is UseCaseResult.Success -> {
                when (val outcome = result.value) {
                    is SignUpOutcome.SessionCreated ->
                        // observeAuthState() will emit Authenticated; the
                        // root navigator switches away from LoginScreen on
                        // that transition. Just clear the submitting flag.
                        form.update { it.copy(isSubmitting = false) }
                    is SignUpOutcome.AwaitingEmailAction ->
                        // Both EmailConfirmationRequired (new signup
                        // pending confirmation) and AlreadyRegistered
                        // (existing email) route to the same "check your
                        // email" UI state — deliberately, to preserve
                        // Supabase's email-enumeration security-by-
                        // obscurity at the UI layer (OWASP A07). The
                        // legitimate owner discovers their existing
                        // account via the "you may already have an
                        // account" hint on the confirmation screen.
                        // Password is cleared so it isn't retained if
                        // the user dismisses and re-enters sign-up flow.
                        form.update {
                            it.copy(
                                isSubmitting = false,
                                emailConfirmationSentTo = outcome.email,
                                password = "",
                            )
                        }
                }
            }
            is UseCaseResult.Failure ->
                form.update { it.copy(isSubmitting = false, error = result.error.toErrorMessage()) }
        }
    }

    private suspend fun handleSignIn(
        email: String,
        password: String,
    ) {
        when (val result = signIn(email, password)) {
            is UseCaseResult.Success ->
                form.update { it.copy(isSubmitting = false) }
            is UseCaseResult.Failure ->
                form.update { it.copy(isSubmitting = false, error = result.error.toErrorMessage()) }
        }
    }
}
