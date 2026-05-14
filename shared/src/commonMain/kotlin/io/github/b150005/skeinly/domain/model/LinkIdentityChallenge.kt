package io.github.b150005.skeinly.domain.model

/**
 * Phase 26.1 (ADR-022 §6.1) — transient UI prompt state surfaced when an
 * OAuth sign-in (Apple in 26.1; Google in 26.2; iOS Google in 26.3)
 * returns the Supabase "user already exists" path. The user must first
 * sign in with their original credentials (typically email/password)
 * then call `auth.linkIdentityWithIdToken(provider, pendingIdToken)` to
 * bind the new OAuth identity to the existing account.
 *
 * This is a UI-layer concept, NOT a [AuthState] variant — the underlying
 * Supabase session status remains Unauthenticated for the duration of
 * the challenge. Surfaced via [io.github.b150005.skeinly.ui.auth.AuthUiState.linkIdentityRequired].
 *
 * Phase 26.4 (ADR-022 §6.3) — extended with [pendingIdToken] + [nonce]
 * so the LinkIdentity form can call Supabase's `linkIdentityWithIdToken`
 * AFTER the user has signed in with their email/password credentials.
 * The pending token is held in-memory only for the duration of the
 * challenge (no persistence) — dismissal drops it; resolution consumes
 * it; an unhandled app death drops it because no retention layer.
 *
 * Why in-memory only: OAuth ID tokens are short-lived (Apple: 10 min,
 * Google: 1 hour) and persisting them would (a) require encrypted
 * storage equivalent to Auth session tokens, (b) introduce a stale-token
 * recovery path for the case where the user closes the app between the
 * challenge and resolving it. The user reproduces the OAuth tap on
 * relaunch — clean.
 *
 * @property email           email address Supabase reported as
 *                           already-registered (carried in the
 *                           existing-user identity row).
 * @property provider        OAuth provider the user attempted to sign in
 *                           with; drives the localized prompt copy AND
 *                           the `linkIdentityWithIdToken(provider, ...)`
 *                           dispatch.
 * @property pendingIdToken  Raw JWT the original OAuth call returned;
 *                           held until the user resolves OR dismisses
 *                           the challenge. Re-submitted as-is to
 *                           Supabase via `linkIdentityWithIdToken`.
 * @property nonce           Plaintext nonce that the original OAuth
 *                           call stamped into the token's `nonce` claim
 *                           (Apple only — Phase 26.1 always supplies;
 *                           Google null per Phase 26.2 + 26.3 nonceless
 *                           posture). Supabase's `linkIdentityWithIdToken`
 *                           verifies the nonce server-side, same as
 *                           `signInWithIdToken`.
 */
data class LinkIdentityChallenge(
    val email: String,
    val provider: OAuthProviderKind,
    val pendingIdToken: String,
    val nonce: String?,
)
