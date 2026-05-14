package io.github.b150005.skeinly.domain.model

sealed interface AuthState {
    data object Loading : AuthState

    data class Authenticated(
        val userId: String,
        val email: String?,
    ) : AuthState

    /**
     * Phase 26.5 (ADR-022 §6.4) — Supabase issued an AAL1 session but
     * the user has a verified MFA factor that elevates the next AAL to
     * AAL2. Root navigator routes to MfaChallengeScreen on this state;
     * the user is technically signed-in (Supabase considers the session
     * Authenticated at AAL1) but must satisfy the second factor before
     * any AAL2-protected resource is reachable.
     *
     * On successful TOTP verification the session JWT is replaced with
     * an AAL2 token + the session-status flow re-emits, which our
     * combined repository observation maps to plain [Authenticated] —
     * at which point the navigator routes off MfaChallengeScreen.
     */
    data class MfaChallengeRequired(
        val userId: String,
        val email: String?,
    ) : AuthState

    data object Unauthenticated : AuthState

    data class Error(
        val message: String,
    ) : AuthState
}
