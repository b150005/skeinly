package io.github.b150005.skeinly.domain.model

/**
 * Phase 26.6 (ADR-022 §6.6) — extracted user_metadata from the currently
 * authenticated session, used by the post-OAuth onboarding gate to
 * pre-fill the "What should we call you?" + "Use this picture?" prompts
 * for new sign-ups via Apple / Google.
 *
 * - [displayName]  — best-effort name pulled from the OAuth provider:
 *     - Apple   → `user_metadata.full_name` (auto-populated by the
 *                 `handle_new_user` SQL trigger on first sign-in only,
 *                 because Apple emits `full_name` only on the *initial*
 *                 ASAuthorizationAppleIDCredential — subsequent sign-ins
 *                 omit it, so this fallback covers re-installs after
 *                 manual profile reset).
 *     - Google  → `user_metadata.name` (always present in the ID token
 *                 claims).
 *     - Email/password sign-ups never populate this field — null here.
 * - [pictureUrl]   — `user_metadata.picture` URL (Google only — Apple
 *                    doesn't expose an avatar URL via the IDToken claims).
 *                    Null for Apple + email/password.
 * - [primaryProvider] — which identity originated the current session.
 *                       Comes from the first entry of `UserInfo.identities`
 *                       (Supabase orders them by creation; the original
 *                       sign-up identity is index 0).
 *
 * Empty / placeholder semantics: a field is null when the OAuth provider
 * did not supply it OR when the value was an empty string after trim.
 * The gate consumer treats null + empty interchangeably to surface the
 * same skip-this-step UI affordance.
 */
data class OAuthOnboardingMetadata(
    val displayName: String?,
    val pictureUrl: String?,
    val primaryProvider: AuthProviderKind,
)
