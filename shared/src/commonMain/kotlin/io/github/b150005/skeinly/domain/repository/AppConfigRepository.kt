package io.github.b150005.skeinly.domain.repository

import io.github.b150005.skeinly.domain.model.AppConfig
import kotlinx.coroutines.flow.StateFlow

/**
 * Phase 39 (W4 / 2026-05-11) — repository fronting the `app_config`
 * Supabase row. Layering: domain interface here, impl in
 * [io.github.b150005.skeinly.data.repository.AppConfigRepositoryImpl]
 * (Supabase RPC + multiplatform-settings cache).
 *
 * State semantics:
 *   - [state] starts at [AppConfigState.Loading] when no cache exists,
 *     or [AppConfigState.Cached] when a cache hit is available at init.
 *   - After [refresh] succeeds, transitions to [AppConfigState.Live]
 *     with the freshly-fetched value. The cache is also updated.
 *   - After [refresh] fails (network error, server 5xx, etc.) AND a
 *     cache hit exists, state stays at [AppConfigState.Cached] — the
 *     gate evaluates against the cached value.
 *   - After [refresh] fails AND no cache exists (first launch +
 *     offline), state transitions to [AppConfigState.Unavailable] —
 *     the gate fails-open (skips the version check entirely) per the
 *     offline-first contract.
 *
 * Callers ([io.github.b150005.skeinly.ui.forceupdate.ForceUpdateGate])
 * read [state] reactively. The repository is constructed at app init
 * time + invokes [refresh] once on startup; further refresh calls are
 * idempotent and only update state on a delta.
 */
interface AppConfigRepository {
    /** Reactive view of the current config + freshness status. */
    val state: StateFlow<AppConfigState>

    /**
     * One-shot refresh from the remote RPC. Updates the cache + state
     * on success. Safe to call repeatedly (the gate calls it once at
     * startup; could extend to a periodic poll post-alpha).
     *
     * Returns:
     *   - [Result.success] when the RPC returned a row (state moves to
     *     [AppConfigState.Live]).
     *   - [Result.failure] otherwise, with the underlying exception.
     *     State does NOT regress (cached values stay; unavailable stays).
     */
    suspend fun refresh(): Result<AppConfig>
}

/**
 * Force-update gate state. Three branches discriminate the three
 * failure modes the gate must distinguish — see [AppConfigRepository]
 * KDoc for the transition graph.
 *
 *   - [Loading]: initial state before the first cache read completes
 *     (transient, sub-millisecond on real devices).
 *   - [Cached]: most recent value is from a previous run's cache, not
 *     this run's network fetch. Gate evaluates normally.
 *   - [Live]: most recent value came from this run's RPC. Gate
 *     evaluates normally.
 *   - [Unavailable]: no cache + no successful fetch. Gate fails-open
 *     (skips evaluation entirely) — offline-first contract.
 */
sealed interface AppConfigState {
    data object Loading : AppConfigState

    data class Cached(
        val config: AppConfig,
    ) : AppConfigState

    data class Live(
        val config: AppConfig,
    ) : AppConfigState

    data object Unavailable : AppConfigState
}
