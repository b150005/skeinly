package io.github.b150005.skeinly.domain.symbol

import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.SubscriptionRepository
import kotlin.time.Clock

/**
 * Phase 41.2a (ADR-016 §4.2) — synchronous Pro entitlement gate consulted by
 * `CompositeSymbolCatalog` (Phase 41.2c) on every Pro-tier symbol access.
 *
 * **Why synchronous.** The catalog's `get(id)` call site runs inside palette
 * rendering and cell drawing — paying a coroutine context switch per symbol
 * access would dwarf the actual rendering cost. The resolver bottoms out at
 * [SubscriptionRepository.cachedActiveSubscription] which itself reads a
 * single PK-indexed SQLDelight row directly.
 *
 * **Offline default-deny.** Cold launch with no network + no cached
 * subscription row returns false. ADR-016 §8 trade-off: a sub-active user
 * on a flight sees Pro packs locked until network returns and refresh
 * lands. The alternative (offline default-allow with cached "last known
 * Pro" timestamp) is rejected because it would let a perpetually-offline
 * user keep Pro indefinitely past expiry.
 *
 * **Clock-manipulation bypass window.** ADR-016 §8 #5 — a user who sets
 * their device clock backward while offline keeps [isPro] true past true
 * expiry. Bounded by:
 * 1. [SubscriptionRepository.refresh] re-validates against the server's
 *    `now()` on next reconnect (remote-side `subscriptions.expires_at` is
 *    a TIMESTAMPTZ; the row reflects server-truth).
 * 2. The `request-pack-download` Edge Function (Phase 41.1.5) re-validates
 *    server-side — the local bypass window only extends access to packs
 *    already on disk, never to new entitlement-gated downloads.
 *
 * If telemetry post-launch surfaces meaningful clock-manipulation abuse,
 * mitigation options live in ADR-016 §8 #5.
 */
class EntitlementResolver(
    private val subscriptionRepository: SubscriptionRepository,
    private val authRepository: AuthRepository,
    private val clock: Clock = Clock.System,
) {
    /**
     * Returns true when the current authenticated user holds an active Pro
     * subscription as of `clock.now()`. Returns false when:
     * - No user is authenticated.
     * - The cached subscription row is missing, refunded, expired, or in
     *   billing retry past the grace window.
     * - The cached row's `expires_at` is in the past.
     *
     * Callers MUST treat this as a UX gate, not a security boundary — the
     * server-side `request-pack-download` Edge Function and `is_pro(uid)`
     * RPC re-validate against authoritative state for every server resource.
     */
    fun isPro(): Boolean {
        val userId = authRepository.getCurrentUserId() ?: return false
        val sub = subscriptionRepository.cachedActiveSubscription(userId) ?: return false
        return sub.isActiveAt(clock.now())
    }
}
