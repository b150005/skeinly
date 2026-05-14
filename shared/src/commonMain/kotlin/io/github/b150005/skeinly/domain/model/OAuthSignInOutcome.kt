package io.github.b150005.skeinly.domain.model

/**
 * Phase 26.1 (ADR-022 §6.1) — outcome of an OAuth sign-in attempt
 * (Apple via Phase 26.1; Google via Phase 26.2). Differentiates the
 * happy-path session creation from the link-identity-required path
 * (Supabase reports email already belongs to another auth method).
 *
 * Hard failures (network, server 5xx, malformed token) are propagated
 * as exceptions, not via this sealed type — the UseCase layer maps
 * them to [io.github.b150005.skeinly.domain.usecase.ErrorMessage].
 */
sealed interface OAuthSignInOutcome {
    /**
     * Supabase issued a session — the active auth-state flow will emit
     * [AuthState.Authenticated] next. The UI clears the submitting flag
     * and the root navigator routes off the LoginScreen on the
     * transition (same shape as the email/password happy path).
     */
    data object SessionCreated : OAuthSignInOutcome

    /**
     * Supabase rejected the sign-in because the email tied to the
     * OAuth identity already exists in `auth.users` under a different
     * auth method (typically email/password — or a different OAuth
     * provider). The user must sign in with the original method, then
     * `auth.linkIdentityWithIdToken(provider, idToken)` from a
     * subsequent session to bind the new OAuth identity.
     *
     * The UI surfaces a "this email already has an account — sign in
     * with your password first" prompt keyed on [provider]. This path
     * is explicitly NOT auto-resolved (OWASP A07 — silent auto-merge
     * is rejected per ADR-022 §6.1 decision (c)).
     *
     * Phase 26.4 (ADR-022 §6.3) — the original [pendingIdToken] +
     * [nonce] are carried back to the ViewModel via this outcome so
     * the resolution-step `linkIdentityWithIdToken(provider, ...)`
     * call can re-submit the same token after the user's password
     * sign-in succeeds. ID token expiry is short (Apple 10 min,
     * Google 1 hour); if the user's password sign-in step takes
     * longer than that, the eventual `linkIdentityWithIdToken` call
     * fails server-side and the user retries from the LoginScreen
     * (a fresh OAuth tap regenerates the token).
     *
     * @property email           address Supabase reported as
     *                           already-registered.
     * @property provider        OAuth provider the user attempted;
     *                           drives the localized prompt copy.
     * @property pendingIdToken  Raw JWT the original OAuth call
     *                           returned, threaded through to the
     *                           link-identity resolution step.
     * @property nonce           Plaintext nonce stamped into the
     *                           token's `nonce` claim (Apple only,
     *                           null for Google — parity with the
     *                           `signInWithIdToken` posture).
     */
    data class LinkIdentityRequired(
        val email: String,
        val provider: OAuthProviderKind,
        val pendingIdToken: String,
        val nonce: String?,
    ) : OAuthSignInOutcome
}
