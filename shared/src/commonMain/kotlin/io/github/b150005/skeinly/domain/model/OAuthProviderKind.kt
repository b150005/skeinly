package io.github.b150005.skeinly.domain.model

/**
 * Phase 26.1 (ADR-022 §6.1) — closed enum identifying which OAuth provider
 * initiated an [io.github.b150005.skeinly.ui.auth.AuthUiState.linkIdentityRequired]
 * challenge. The UI surfaces "this email already exists — sign in with
 * <provider>" copy keyed on this value so users see the same identity
 * they originally registered with rather than a generic provider name.
 *
 * Phase 26.1 ships only [Apple]; [Google] lands in Phase 26.2. The enum
 * is closed to keep `when` exhaustiveness honest at every consumer site.
 */
enum class OAuthProviderKind {
    Apple,
    Google,
}
