package io.github.b150005.skeinly.data.repository

import io.github.b150005.skeinly.data.local.LocalSubscriptionDataSource
import io.github.b150005.skeinly.data.remote.SubscriptionRemoteOperations
import io.github.b150005.skeinly.domain.model.Subscription
import io.github.b150005.skeinly.domain.repository.SubscriptionRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * Phase 41.2a (ADR-016 §4.2) implementation.
 *
 * **Local-first reads, remote-driven cache fills.** Since the client never
 * originates a write, the local cache is purely a refresh mirror — there is
 * no "local change pending sync" state to coordinate. [refresh] is the sole
 * cache-fill path; callers (app foreground hook, Phase 41.3 post-purchase
 * callback) drive it.
 *
 * **Empty-rowset eviction is staleness-gated.** When the remote returns no
 * rows for [userId], we evict the local cache only if the cached row's
 * `lastVerifiedAt` is older than [STALE_THRESHOLD]. Two failure modes
 * motivate this guard:
 * 1. Server-side row removal (e.g. account deletion via auth.users CASCADE)
 *    — eviction is correct.
 * 2. Transient JWT expiry / RLS scoping artifact where the eq filter
 *    legitimately returns zero rows under a stale token — eviction would
 *    spuriously drop a still-valid Pro row, causing isPro() to flip false
 *    until the next successful refresh.
 * The staleness gate keeps the cache during transient artifacts and lets
 * a real removal land within one staleness window. A row that remote
 * stops returning AND was last verified > 24h ago is treated as a real
 * removal.
 *
 * **Note on `verify-receipt` semantics.** The Edge Function uses upsert
 * for refund / status flips (`status = 'refunded'`) — it does NOT delete
 * the row. The eviction path therefore only matters for the auth.users
 * CASCADE deletion case + transient artifacts. A refunded row stays in
 * the cache and is correctly filtered out of "active" by the
 * `getActiveForUser` query.
 *
 * Constructor [remote] is nullable so local-only mode (no Supabase) keeps
 * [refresh] as a no-op returning the cached value.
 */
class SubscriptionRepositoryImpl(
    private val local: LocalSubscriptionDataSource,
    private val remote: SubscriptionRemoteOperations?,
    private val isOnline: StateFlow<Boolean>,
    private val clock: Clock = Clock.System,
) : SubscriptionRepository {
    companion object {
        /**
         * Staleness threshold for the empty-rowset eviction path. A cached
         * row whose `lastVerifiedAt` is newer than this is preserved on a
         * remote-returns-empty response (treated as a transient artifact);
         * older rows are evicted (treated as a real removal).
         */
        internal val STALE_THRESHOLD = 24.hours
    }

    override fun cachedActiveSubscription(userId: String): Subscription? = local.getActiveForUserSync(userId)

    override fun observeActiveSubscription(userId: String): Flow<Subscription?> = local.observeActiveForUser(userId)

    override suspend fun refresh(userId: String): Result<Subscription?> {
        val r = remote
        if (r == null || !isOnline.value) {
            // Local-only mode or offline — surface the cached value as success
            // so callers don't treat "no remote configured" as an error path.
            // Distinguishing the two is the job of higher-layer telemetry
            // (which knows whether Supabase is configured); the repository
            // contract is "return whatever you can answer with right now".
            return Result.success(local.getActiveForUser(userId))
        }
        return try {
            val rows = r.getAllForUser(userId)
            if (rows.isEmpty()) {
                // Staleness-gated eviction: a remote-returns-empty response
                // could be a real removal (auth.users CASCADE) OR a transient
                // RLS scoping artifact under a stale JWT. Preserve a recently-
                // verified cached row; evict only after [STALE_THRESHOLD]
                // elapses without remote confirmation.
                val cached = local.getActiveForUser(userId)
                val now = clock.now()
                val isStale = cached == null || cached.lastVerifiedAt < now - STALE_THRESHOLD
                if (isStale) {
                    local.clearForUser(userId)
                }
            } else {
                rows.forEach { local.upsert(it) }
            }
            Result.success(local.getActiveForUser(userId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun clearLocalCache(userId: String) {
        local.clearForUser(userId)
    }
}
