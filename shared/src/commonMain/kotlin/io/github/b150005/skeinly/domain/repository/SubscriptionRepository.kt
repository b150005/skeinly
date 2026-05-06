package io.github.b150005.skeinly.domain.repository

import io.github.b150005.skeinly.domain.model.Subscription
import kotlinx.coroutines.flow.Flow

/**
 * Phase 41.2a (ADR-016 §4.2) — read-only access to the per-user subscription
 * row that gates Pro entitlement.
 *
 * **Read-only at the client.** The `verify-receipt` Edge Function with the
 * service-role key is the only writer (migration 017). The repository
 * exposes [refresh] for caller-driven cache fills (app foreground, post-
 * purchase RevenueCat callback in Phase 41.3, cold launch) but not a write
 * method.
 *
 * **No Realtime channel (ADR-016 §10 Q2 resolution, 41.2a).** Migration
 * 017 lines 154-165 deliberately disable Realtime for `subscriptions` —
 * subscription state is not push-time-sensitive the way collab
 * notifications are, and an 8th channel against the free-tier cap is not
 * justified for a single-row mirror. The cache stays warm via [refresh]
 * dispatched from auth + lifecycle hooks.
 *
 * **Synchronous read.** [cachedActiveSubscription] is intentionally
 * non-suspend so [io.github.b150005.skeinly.domain.symbol.EntitlementResolver.isPro]
 * can answer on the catalog hot path without a coroutine context switch.
 * The underlying SQLDelight call is a single PK-indexed row lookup.
 */
interface SubscriptionRepository {
    /**
     * Synchronous read of the current active-or-grace subscription row for
     * [userId]. Returns null when the user has no subscription row OR no
     * row is in an active billing state.
     *
     * The "is this row Pro right now" gate (status check + expiresAt) lives
     * at [Subscription.isActiveAt] / [Subscription.isActiveNow] — callers
     * should evaluate that against the current clock rather than treating
     * a non-null return as "Pro".
     */
    fun cachedActiveSubscription(userId: String): Subscription?

    /**
     * Reactive observation of the current active row. Emits null when no
     * active row exists. ViewModels use this to react to state flips
     * (refund flips status to REFUNDED → emission becomes null).
     */
    fun observeActiveSubscription(userId: String): Flow<Subscription?>

    /**
     * Best-effort remote refresh — fetches the user's `subscriptions` rows
     * via the Supabase REST surface and writes them through to the local
     * cache. Returns the active subscription if the user holds one after
     * the refresh, null otherwise.
     *
     * Throws nothing — failures (no network, remote unavailable, decode
     * error) leave the cache as-is and return the cached active row, if
     * any. Callers can distinguish "cache up-to-date with empty result"
     * from "refresh failed" via the [Result] envelope.
     *
     * **Deliberate deviation from other repository methods.** Most
     * coordinator repositories (`PatternRepository.getById`, etc.) return
     * the domain type directly (or null for not-found) and rethrow on
     * failure. This method returns [Result] because callers genuinely
     * need to distinguish "verified-no-row" (definitively not Pro) from
     * "could-not-verify" (state unknown — show "subscription status
     * unverified" affordance in Settings). Future maintainers: do NOT
     * port the [Result] shape to other repository methods on the basis
     * of consistency; the signal differentiation here is the only reason.
     */
    suspend fun refresh(userId: String): Result<Subscription?>

    /**
     * Logout cleanup — clears every cached row for [userId]. The
     * `auth.users` CASCADE handles the server side.
     */
    suspend fun clearLocalCache(userId: String)
}
