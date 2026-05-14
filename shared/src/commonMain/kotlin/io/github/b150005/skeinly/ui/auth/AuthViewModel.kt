package io.github.b150005.skeinly.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.auth.OAuthIdTokenResult
import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.model.LinkIdentityChallenge
import io.github.b150005.skeinly.domain.model.OAuthSignInOutcome
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
    /**
     * Phase 26.1 (ADR-022 §6.1) — non-null while the login screen is in
     * the "this email already has an account — sign in with your
     * password first" prompt state, after an OAuth sign-in (Apple in
     * 26.1, Google in 26.2) returned [OAuthSignInOutcome.LinkIdentityRequired].
     * Surfaced as a separate transient UI state rather than as an
     * [AuthState] variant so the underlying session-status semantics
     * stay clean (Supabase still considers the user Unauthenticated
     * during the challenge).
     */
    val linkIdentityRequired: LinkIdentityChallenge? = null,
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

    /**
     * Phase 26.1 (ADR-022 §6.1) — incoming Apple ID token from the
     * SwiftUI `SignInWithAppleButton` completion handler, surfaced
     * through the Koin-resolved `KoinHelperKt.handleAppleIdToken(...)`
     * bridge. The ViewModel forwards to the repository and routes the
     * outcome (SessionCreated → clear submit; LinkIdentityRequired →
     * surface the link-identity prompt; exception → form.error).
     */
    data class SignInWithAppleIdToken(
        val idToken: String,
        val nonce: String,
    ) : AuthEvent

    /**
     * Phase 26.1 — user-initiated dismissal of the
     * [AuthUiState.linkIdentityRequired] prompt. Used when the user
     * decides to retry with a different provider, or to abandon the
     * OAuth attempt entirely.
     */
    data object DismissLinkIdentityPrompt : AuthEvent

    /**
     * Phase 26.2 (ADR-022 §6.2) — user tapped the Continue with Google
     * button on LoginScreen. The ViewModel fires
     * `OAuthClient.acquireGoogleIdToken()` (platform-specific
     * Credential Manager flow on Android; Failure stub on iOS until
     * Phase 26.3), then forwards the resulting ID token to
     * `AuthRepository.signInWithGoogle(idToken, nonce)`.
     */
    data object SignInWithGoogle : AuthEvent
}

private data class FormState(
    val email: String = "",
    val password: String = "",
    val isSignUp: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: ErrorMessage? = null,
    val emailConfirmationSentTo: String? = null,
    val linkIdentityRequired: LinkIdentityChallenge? = null,
)

class AuthViewModel(
    private val observeAuthState: ObserveAuthStateUseCase,
    private val signIn: SignInUseCase,
    private val signUp: SignUpUseCase,
    /**
     * Phase 26.1 (ADR-022 §6.1) — Apple sign-in repository hook,
     * passed as a lambda-seam to keep the VM testable without
     * pulling the full repository surface (mirrors the
     * `BugReportPreviewViewModel.submit` precedent from Phase 39.5).
     * Production wiring resolves an `AuthRepository` from Koin and
     * binds `authRepository::signInWithApple`.
     */
    private val signInWithApple: suspend (idToken: String, nonce: String) -> OAuthSignInOutcome,
    /**
     * Phase 26.2 (ADR-022 §6.2) — Google sign-in repository hook
     * (same lambda-seam pattern as `signInWithApple` above).
     */
    private val signInWithGoogle: suspend (idToken: String, nonce: String?) -> OAuthSignInOutcome,
    /**
     * Phase 26.2 — platform-specific Google ID-token acquisition
     * surface. The Android actual fires Credential Manager; the iOS
     * actual returns Failure (iOS Google sign-in lands in Phase 26.3).
     */
    private val acquireGoogleIdToken: suspend () -> OAuthIdTokenResult,
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
                linkIdentityRequired = formState.linkIdentityRequired,
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
            is AuthEvent.SignInWithAppleIdToken -> handleAppleIdToken(event.idToken, event.nonce)
            AuthEvent.DismissLinkIdentityPrompt ->
                form.update { it.copy(linkIdentityRequired = null) }
            AuthEvent.SignInWithGoogle -> handleGoogleSignIn()
        }
    }

    /**
     * Phase 26.1 (ADR-022 §6.1) — forwards an Apple-issued ID token
     * through the repository, then maps the outcome onto form state.
     * Re-entrant guard via [FormState.isSubmitting] mirrors the email
     * path so a double-tap on SignInWithAppleButton cannot fire twice.
     */
    private fun handleAppleIdToken(
        idToken: String,
        nonce: String,
    ) {
        val current = form.value
        if (current.isSubmitting) return
        viewModelScope.launch {
            form.update { it.copy(isSubmitting = true, error = null) }
            try {
                when (val outcome = signInWithApple(idToken, nonce)) {
                    is OAuthSignInOutcome.SessionCreated ->
                        form.update { it.copy(isSubmitting = false) }
                    is OAuthSignInOutcome.LinkIdentityRequired ->
                        form.update {
                            it.copy(
                                isSubmitting = false,
                                linkIdentityRequired =
                                    LinkIdentityChallenge(
                                        email = outcome.email,
                                        provider = outcome.provider,
                                    ),
                            )
                        }
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Map any server-side / network failure to the generic
                // bucket — the email/password path already maps known
                // Supabase auth-error codes via `toErrorMessage()`, but
                // the IDToken path surfaces a narrower set (token
                // verification + nonce mismatch) that doesn't have
                // user-facing-distinct copy in 26.1. Phase 26+ can
                // refine if testers report ambiguous error UX.
                form.update {
                    it.copy(
                        isSubmitting = false,
                        error = ErrorMessage.Generic,
                    )
                }
            }
        }
    }

    /**
     * Phase 26.2 (ADR-022 §6.2) — Google sign-in flow. Two steps:
     *   1. `acquireGoogleIdToken()` → platform Credential Manager
     *      (Android) or Failure stub (iOS, until 26.3) →
     *      [OAuthIdTokenResult].
     *   2. On Success, forward the ID token + optional nonce to
     *      `signInWithGoogle(...)`. `UserCancelled` silently clears
     *      the submitting flag (no banner — matches Apple cancel UX).
     *      `Failure` surfaces a generic error.
     */
    private fun handleGoogleSignIn() {
        val current = form.value
        if (current.isSubmitting) return
        viewModelScope.launch {
            form.update { it.copy(isSubmitting = true, error = null) }
            val acquisition =
                try {
                    acquireGoogleIdToken()
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    OAuthIdTokenResult.Failure(message = e.message.orEmpty())
                }
            when (acquisition) {
                is OAuthIdTokenResult.Success -> forwardGoogleIdToken(acquisition.idToken, acquisition.nonce)
                OAuthIdTokenResult.UserCancelled ->
                    form.update { it.copy(isSubmitting = false) }
                is OAuthIdTokenResult.Failure ->
                    form.update {
                        it.copy(
                            isSubmitting = false,
                            error = ErrorMessage.Generic,
                        )
                    }
            }
        }
    }

    private suspend fun forwardGoogleIdToken(
        idToken: String,
        nonce: String?,
    ) {
        try {
            when (val outcome = signInWithGoogle(idToken, nonce)) {
                is OAuthSignInOutcome.SessionCreated ->
                    form.update { it.copy(isSubmitting = false) }
                is OAuthSignInOutcome.LinkIdentityRequired ->
                    form.update {
                        it.copy(
                            isSubmitting = false,
                            linkIdentityRequired =
                                LinkIdentityChallenge(
                                    email = outcome.email,
                                    provider = outcome.provider,
                                ),
                        )
                    }
            }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Throwable) {
            form.update {
                it.copy(
                    isSubmitting = false,
                    error = ErrorMessage.Generic,
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
