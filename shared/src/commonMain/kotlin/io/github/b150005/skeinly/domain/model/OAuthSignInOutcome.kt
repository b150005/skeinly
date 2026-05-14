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
     * `auth.linkIdentity(provider)` from a subsequent session to bind
     * the new OAuth identity.
     *
     * The UI surfaces a "this email already has an account — sign in
     * with your password first" prompt keyed on [provider]. This path
     * is explicitly NOT auto-resolved (OWASP A07 — silent auto-merge
     * is rejected per ADR-022 §6.1 decision (c)).
     *
     * @property email    address Supabase reported as already-registered.
     * @property provider OAuth provider the user attempted; drives the
     *                    localized prompt copy.
     */
    data class LinkIdentityRequired(
        val email: String,
        val provider: OAuthProviderKind,
    ) : OAuthSignInOutcome
}
