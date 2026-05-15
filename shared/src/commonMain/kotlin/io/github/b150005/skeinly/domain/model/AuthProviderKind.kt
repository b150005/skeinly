package io.github.b150005.skeinly.domain.model

/**
 * Phase 26.6 (ADR-022 §6.6) — closed enum identifying the sign-in
 * method that originated a Supabase identity. Distinct from
 * [OAuthProviderKind] (which is OAuth-only) because email/password is
 * a first-class identity in Supabase Auth and needs to be discriminated
 * at the Settings → Account display layer.
 *
 * Mapping from `UserInfo.identities[].provider`:
 *   - "email"   → [Email]
 *   - "apple"   → [Apple]
 *   - "google"  → [Google]
 *
 * Any future provider Supabase adds (phone, github, …) would either
 * collapse into one of these arms via a new enum variant or fall into
 * a dedicated "unknown" bucket. Keeping the enum closed forces a
 * conscious decision when Supabase exposes a new provider via the
 * dashboard — surfacing the new identity in Settings is a deliberate
 * UI act, not a silent passthrough.
 */
enum class AuthProviderKind {
    Email,
    Apple,
    Google,
}
