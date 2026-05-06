package io.github.b150005.skeinly.data.remote

import io.github.b150005.skeinly.domain.model.SymbolPackPayload

/**
 * Phase 41.2b (ADR-016 §3.3, §4.3) — outcome of a single
 * `request-pack-download` Edge Function call + signed-URL payload fetch.
 *
 * The 4 HTTP error shapes the Edge Function defines (401 / 403 / 404 / 429)
 * each surface as a distinct [Failure] subtype because the
 * [io.github.b150005.skeinly.data.sync.SymbolPackSyncManager] dispatches
 * differently on each — 401 retries on next session after auth refresh,
 * 403 silently skips this pack until the entitlement Realtime push
 * restores it, 404 logs + skips (server-side archived), 429 backs off
 * exponentially. Collapsing them into a generic "failure" would force
 * the sync manager to re-parse the body.
 *
 * [Network] covers transport-level failures (DNS, connection refused, TLS,
 * timeout). [Parse] covers the payload-fetch path returning malformed JSON
 * — distinct from [Network] because a Parse failure is a server-side bug
 * worth surfacing to telemetry as a real defect, while a Network failure
 * is the mundane offline-on-flight path that should silently retry.
 */
sealed interface SymbolPackDownloadResult {
    /**
     * The signed-URL fetch returned a parsed [SymbolPackPayload]; [version]
     * is the Edge Function's reported `current_version` at sign-time. The
     * caller writes both `(packId, version)` to the local payload table.
     */
    data class Success(
        val payload: SymbolPackPayload,
        val version: Int,
    ) : SymbolPackDownloadResult

    sealed interface Failure : SymbolPackDownloadResult {
        /** 401 — JWT missing or expired. Sync defers to next session. */
        data object Unauthenticated : Failure

        /**
         * 403 — `subscriptions` row is missing or expired for a Pro pack.
         * Sync silently skips; the pack lands once a Realtime push
         * (`subscriptions-<userId>`) restores the active row and the next
         * sync cycle re-attempts.
         */
        data class ProEntitlementRequired(
            val packId: String,
        ) : Failure

        /**
         * 404 — `symbol_packs` has no row for [packId]. Either a stale
         * client manifest (rare; we just fetched the manifest) or a
         * server-side archive. Sync logs + skips the pack.
         */
        data class PackNotFound(
            val packId: String,
        ) : Failure

        /**
         * 429 — per-user sliding-window rate limit hit. [retryAfterSeconds]
         * is the Edge Function's hint; sync uses it as the lower bound for
         * exponential backoff on next launch.
         */
        data class RateLimited(
            val retryAfterSeconds: Int,
        ) : Failure

        /**
         * Network or transport failure — DNS, TCP, TLS, timeout, etc.
         * Sync silently skips; next launch retries with no backoff.
         */
        data class Network(
            val cause: Throwable,
        ) : Failure

        /**
         * Decoded JSON didn't match the expected envelope (Edge Function
         * response or signed-URL payload). Surfaces as a Sentry warning —
         * a server-side bug we want visible.
         */
        data class Parse(
            val cause: Throwable,
        ) : Failure

        /**
         * Any other non-2xx status the Edge Function might emit (500,
         * misconfigured, etc.). Sync logs + skips.
         */
        data class Unknown(
            val statusCode: Int,
            val body: String?,
        ) : Failure
    }
}
