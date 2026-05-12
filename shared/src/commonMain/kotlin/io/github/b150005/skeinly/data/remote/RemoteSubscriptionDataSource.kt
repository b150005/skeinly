package io.github.b150005.skeinly.data.remote

import io.github.b150005.skeinly.domain.model.Subscription
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order

/**
 * Phase 41.2a (ADR-016 §3.1, §4.2) — read surface contract for the
 * `subscriptions` table that `SubscriptionRepositoryImpl` consumes.
 *
 * Exists as an interface so the repository test can inject an in-memory
 * fake without standing up Supabase. Same precedent as
 * [io.github.b150005.skeinly.domain.repository.SuggestionApplyOperations]
 * (ADR-014 §5).
 */
interface SubscriptionRemoteOperations {
    /**
     * Fetches every subscription row visible to [userId] under RLS, ordered
     * newest-first by `updated_at`. Throws on network / decode failure.
     */
    suspend fun getAllForUser(userId: String): List<Subscription>
}

/**
 * Phase 41.2a (ADR-016 §3.1, §4.2) — Supabase implementation of
 * [SubscriptionRemoteOperations].
 *
 * **Read-only on purpose.** Migration 017 lines 83-86 deliberately omit
 * INSERT / UPDATE / DELETE policies for the public role; the
 * `revenuecat-webhook` Edge Function (Phase 39 prep, 2026-05-08) with the
 * service-role key is the sole writer (via the
 * `upsert_subscription_from_webhook` SECURITY DEFINER RPC, migration 023).
 * Surfacing a write method here would compile but every call would 401
 * with "new row violates row-level security policy" — better to have no
 * write surface at all.
 *
 * The `eq("user_id", userId)` filter is defense-in-depth + diagnostic
 * clarity; RLS already scopes to `auth.uid()`. Most users have a single
 * row; alpha testers who later subscribe paid tier hold two (alpha-grant +
 * ios/android), and the active row is picked client-side via the
 * most-recent `updated_at` in [io.github.b150005.skeinly.data.local.LocalSubscriptionDataSource.getActiveForUser].
 */
class RemoteSubscriptionDataSource(
    private val supabaseClient: SupabaseClient,
) : SubscriptionRemoteOperations {
    private val table get() = supabaseClient.postgrest["subscriptions"]

    override suspend fun getAllForUser(userId: String): List<Subscription> =
        table
            .select {
                filter { eq("user_id", userId) }
                order("updated_at", Order.DESCENDING)
            }.decodeList()
}
