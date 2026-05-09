package io.github.b150005.skeinly.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import io.github.b150005.skeinly.data.mapper.toDbString
import io.github.b150005.skeinly.data.mapper.toDomain
import io.github.b150005.skeinly.db.SkeinlyDatabase
import io.github.b150005.skeinly.domain.model.Subscription
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Phase 41.2a (ADR-016 §3.1, §4.2) — local mirror of `public.subscriptions`.
 *
 * **Two read shapes:**
 * - [getActiveForUser] / [getAllForUser] — suspend, dispatcher-confined. Use
 *   from ViewModels and refresh paths.
 * - [getActiveForUserSync] — direct, no dispatcher hop. Use ONLY from
 *   `EntitlementResolver.isPro()` which must answer synchronously on the
 *   catalog hot path. The underlying SQLDelight call is a single PK-indexed
 *   row lookup (microseconds); paying a coroutine context switch on every
 *   `SymbolCatalog.get()` is the cost we deliberately avoid here.
 *
 * Writes happen exclusively via [upsert] from
 * `SubscriptionRepositoryImpl.refresh` after a successful remote fetch. The
 * client never originates a subscription row — the `revenuecat-webhook`
 * Edge Function (Phase 39 prep, 2026-05-08; calls `upsert_subscription_from_webhook`
 * SECURITY DEFINER RPC from migration 023) is the sole authoritative
 * writer (migration 017 lines 83-86 omit public-role write policies).
 */
class LocalSubscriptionDataSource(
    private val db: SkeinlyDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val queries get() = db.subscriptionQueries

    suspend fun getActiveForUser(userId: String): Subscription? =
        withContext(ioDispatcher) {
            queries.getActiveForUser(userId).executeAsOneOrNull()?.toDomain()
        }

    /**
     * Synchronous variant for the EntitlementResolver hot path. Bypasses the
     * IO dispatcher because the catalog `get(id)` call site must answer
     * without suspending. SQLDelight's `executeAsOneOrNull` blocks the calling
     * thread; for a PK-indexed row lookup this is microsecond-bounded and
     * acceptable on the UI thread (compare with the Compose `Modifier.then`
     * cost of the symbol render that follows).
     */
    fun getActiveForUserSync(userId: String): Subscription? = queries.getActiveForUser(userId).executeAsOneOrNull()?.toDomain()

    suspend fun getAllForUser(userId: String): List<Subscription> =
        withContext(ioDispatcher) {
            queries.getAllForUser(userId).executeAsList().map { it.toDomain() }
        }

    fun observeActiveForUser(userId: String): Flow<Subscription?> =
        queries
            .observeActiveForUser(userId)
            .asFlow()
            .mapToOneOrNull(ioDispatcher)
            .map { row -> row?.toDomain() }

    /**
     * Idempotent INSERT-OR-REPLACE keyed on `id`. Refresh paths simply
     * overwrite the row; a re-fetch that surfaces the same row is a silent
     * no-op modulo updated_at touch.
     */
    suspend fun upsert(subscription: Subscription): Subscription =
        withContext(ioDispatcher) {
            queries.upsert(
                id = subscription.id,
                user_id = subscription.userId,
                platform = subscription.platform.toDbString(),
                product_id = subscription.productId,
                status = subscription.status.toDbString(),
                original_transaction_id = subscription.originalTransactionId,
                expires_at = subscription.expiresAt?.toString(),
                is_in_trial = if (subscription.isInTrial) 1L else 0L,
                auto_renew_status = if (subscription.autoRenewStatus) 1L else 0L,
                last_verified_at = subscription.lastVerifiedAt.toString(),
                created_at = subscription.createdAt.toString(),
                updated_at = subscription.updatedAt.toString(),
            )
            subscription
        }

    suspend fun clearForUser(userId: String): Unit =
        withContext(ioDispatcher) {
            queries.clearForUser(userId)
        }
}
