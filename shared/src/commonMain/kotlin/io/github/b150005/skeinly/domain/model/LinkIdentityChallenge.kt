package io.github.b150005.skeinly.domain.model

/**
 * Phase 26.1 (ADR-022 §6.1) — transient UI prompt state surfaced when an
 * OAuth sign-in (Apple in 26.1; Google in 26.2) returns the Supabase
 * "user already exists" path. The user must first sign in with their
 * original credentials (typically email/password) then call
 * `auth.linkIdentity(provider)` to bind the new OAuth identity to the
 * existing account.
 *
 * This is a UI-layer concept, NOT a [AuthState] variant — the underlying
 * Supabase session status remains Unauthenticated for the duration of
 * the challenge. Surfaced via [io.github.b150005.skeinly.ui.auth.AuthUiState.linkIdentityRequired].
 *
 * @property email   email address Supabase reported as already-registered
 *                   (carried in the existing-user identity row).
 * @property provider OAuth provider the user attempted to sign in with;
 *                    drives the localized prompt copy.
 */
data class LinkIdentityChallenge(
    val email: String,
    val provider: OAuthProviderKind,
)
