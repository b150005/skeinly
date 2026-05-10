package io.github.b150005.skeinly.data.remote

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 24.2e (ADR-017 §3.5) — write-only contract for the
 * `device_tokens` table that
 * [io.github.b150005.skeinly.data.repository.DeviceTokenRepositoryImpl]
 * consumes.
 *
 * Exists as an interface so the repository test can inject an
 * in-memory fake without standing up Supabase. Same precedent as
 * [io.github.b150005.skeinly.data.remote.SubscriptionRemoteOperations]
 * (ADR-016 §4.2) and
 * [io.github.b150005.skeinly.domain.repository.SuggestionMergeOperations]
 * (ADR-014 §5).
 */
interface DeviceTokenRemoteOperations {
    /**
     * Upserts the freshly acquired push token. Throws on transport
     * failure; the repository wraps the exception in
     * [io.github.b150005.skeinly.domain.usecase.UseCaseError].
     *
     * @param userId Resolved by the repository before this call —
     *   guaranteed non-null at this layer.
     * @param token Opaque APNs hex / FCM Base64-URL string. Caller MUST
     *   pass through verbatim.
     * @param platform Wire constant (`"ios"` / `"android"`) per migration 025
     *   CHECK. Already serialized at the [io.github.b150005.skeinly.domain.repository.PushPlatform]
     *   enum boundary.
     * @param locale BCP-47 tag — `"en-US"` / `"ja-JP"` only per
     *   migration 025 CHECK.
     */
    suspend fun upsert(
        userId: String,
        token: String,
        platform: String,
        locale: String,
    )
}

/**
 * Phase 24.2e (ADR-017 §3.5) — Supabase implementation of
 * [DeviceTokenRemoteOperations].
 *
 * Sends `Prefer: return=minimal` (Postgrest default when no `select(...)`
 * clause is provided) so the round-trip stays small on the hot
 * foreground path. The composite UNIQUE
 * `(user_id, platform, token)` from migration 025 line 46 makes
 * repeat upserts no-op at the DB layer; the `BEFORE UPDATE` trigger
 * touches `updated_at` so the row stays "fresh" across rotations.
 *
 * **`onConflict` value MUST match the migration 025 UNIQUE column
 * order verbatim.** Postgrest passes the string verbatim to PostgreSQL's
 * `ON CONFLICT (...) DO UPDATE`; a mismatched order silently falls
 * through to a no-op INSERT path that breaks idempotency.
 */
class RemoteDeviceTokenDataSource(
    private val supabaseClient: SupabaseClient,
) : DeviceTokenRemoteOperations {
    override suspend fun upsert(
        userId: String,
        token: String,
        platform: String,
        locale: String,
    ) {
        val row =
            DeviceTokenUpsertPayload(
                userId = userId,
                platform = platform,
                token = token,
                locale = locale,
            )
        supabaseClient.postgrest["device_tokens"]
            .upsert(row) {
                onConflict = ON_CONFLICT_COLUMNS
            }
    }

    @Serializable
    private data class DeviceTokenUpsertPayload(
        @SerialName("user_id") val userId: String,
        val platform: String,
        val token: String,
        val locale: String,
    )

    internal companion object {
        // Composite UNIQUE per migration 025 line 46. Order is
        // load-bearing — Postgrest passes verbatim to PostgreSQL's
        // `ON CONFLICT (...) DO UPDATE`, and a re-ordered constraint
        // alter would silently degrade conflict resolution to a no-op
        // INSERT. Test anchor in DeviceTokenRepositoryImplTest locks
        // the expected string.
        internal const val ON_CONFLICT_COLUMNS = "user_id,platform,token"
    }
}
