package io.github.b150005.skeinly.domain.model

/**
 * Phase 26.6 (ADR-022 §6.6) — single auth identity attached to the
 * current Supabase user, used by the Settings → Account row to surface
 * "Signed in as <email> via Apple / Google / email" alongside the full
 * linked-identities list once Phase 26.4 linkIdentity merges have run.
 *
 * - [provider] — which sign-in method this identity uses.
 * - [email]    — the email associated with the identity. For Apple's
 *                private relay, this is the
 *                `<random>@privaterelay.appleid.com` placeholder Supabase
 *                received from Apple — distinguishable via
 *                [isAppleRelay], which the UI uses to surface a more
 *                specific "using Apple's private email relay" label.
 *                Null when the provider does not expose an email
 *                (defensive — Supabase always sets one in practice).
 */
data class LinkedIdentity(
    val provider: AuthProviderKind,
    val email: String?,
) {
    /**
     * True when this identity is Apple's "Hide My Email" relay. The
     * relay-mail signal is a stable suffix match on the email host;
     * Apple does not expose a dedicated "is relay" claim in the
     * IDToken or `UserInfo`. False for non-Apple identities by
     * definition.
     */
    val isAppleRelay: Boolean
        get() =
            provider == AuthProviderKind.Apple &&
                email != null &&
                email.endsWith("@privaterelay.appleid.com", ignoreCase = true)
}
