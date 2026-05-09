package io.github.b150005.skeinly.domain.repository

import io.github.b150005.skeinly.domain.usecase.UseCaseResult

/**
 * Phase 24.2e (ADR-017 §3.5) — write-only contract for the
 * `device_tokens` table.
 *
 * Each platform's [io.github.b150005.skeinly.notifications.PushTokenRegistrar]
 * actual calls [upsertToken] after successfully acquiring an APNs / FCM
 * registration token, so the `notify-on-write` Edge Function (Phase 24.1)
 * can fan out localized push payloads keyed on `(user_id, platform)`.
 *
 * **Write-only on purpose.** Migration 025 RLS allows clients to
 * SELECT/INSERT/UPDATE/DELETE their own `(user_id, platform, token)`
 * triples; reading other users' rows is structurally impossible. The
 * client never reads its own tokens back — token rotation is handled at
 * the OS layer (FCM SDK / APNs) and re-upserted on every app foreground.
 * Surfacing a read method would compile but wouldn't have a caller.
 *
 * **Idempotent on `(user_id, platform, token)`** — the composite UNIQUE
 * key (migration 025 line 46) makes repeat upserts no-op; the
 * `BEFORE UPDATE` trigger touches `updated_at` so the row stays "fresh"
 * across rotations.
 *
 * **Never throws** — surfaces failures via [UseCaseResult.Failure] so
 * callers can surface a localized error or silently no-op without
 * try/catch boilerplate at every call site.
 */
interface DeviceTokenRepository {
    /**
     * Upserts the freshly acquired push token for the currently
     * authenticated user. Returns:
     * - [UseCaseResult.Success] on a 200/201 from PostgREST.
     * - [UseCaseResult.Failure] with [io.github.b150005.skeinly.domain.usecase.UseCaseError.RequiresConnectivity]
     *   when Supabase is not configured (local-only build).
     * - [UseCaseResult.Failure] with [io.github.b150005.skeinly.domain.usecase.UseCaseError.SignInRequired]
     *   when no session is active (the RLS WITH CHECK clause
     *   `auth.uid() = user_id` would reject the row anyway, but we
     *   short-circuit before the network round-trip).
     * - [UseCaseResult.Failure] with [io.github.b150005.skeinly.domain.usecase.UseCaseError.Network]
     *   or [io.github.b150005.skeinly.domain.usecase.UseCaseError.Unknown]
     *   on transport / RLS / decode failures.
     *
     * @param token Opaque registration token from the OS — APNs hex
     *   string (64 chars) on iOS, FCM Base64-URL token (~163 chars) on
     *   Android. Caller MUST pass through verbatim; the `notify-on-write`
     *   Edge Function reads this value as the credential it signs with
     *   APNs `.p8` / FCM SA OAuth flow.
     * @param platform Wire-format constant — `"ios"` or `"android"`,
     *   matching the migration 025 CHECK constraint. Closed enum at the
     *   call site via [PushPlatform.wireValue] keeps the cardinality
     *   discipline that the `device_tokens.platform CHECK` clause
     *   enforces structurally.
     * @param locale BCP-47 tag (`"en-US"` / `"ja-JP"`). Migration 025
     *   CHECK rejects anything else; caller MUST normalize on the
     *   platform actual side.
     */
    suspend fun upsertToken(
        token: String,
        platform: PushPlatform,
        locale: String,
    ): UseCaseResult<Unit>
}

/**
 * Phase 24.2e (ADR-017 §3.5) — closed enum for `device_tokens.platform`.
 *
 * The wire constants `"ios"` / `"android"` MUST match the migration 025
 * CHECK constraint verbatim. Adding a 3rd platform requires (a) altering
 * the table CHECK + (b) extending the Edge Function fan-out path + (c)
 * adding a new entry here. The structural redundancy is intentional —
 * a future "watchos" entry that lands here without the matching CHECK
 * alter would surface as a server-side rejection rather than a silent
 * mislabeled row.
 */
enum class PushPlatform(
    val wireValue: String,
) {
    IOS("ios"),
    ANDROID("android"),
}
