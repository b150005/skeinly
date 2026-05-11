package io.github.b150005.skeinly.data.repository

import io.github.b150005.skeinly.data.preferences.AppConfigPreferences
import io.github.b150005.skeinly.data.remote.RemoteAppConfigDataSource
import io.github.b150005.skeinly.domain.model.AppConfig
import io.github.b150005.skeinly.domain.repository.AppConfigRepository
import io.github.b150005.skeinly.domain.repository.AppConfigState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 39 (W4 / 2026-05-11) — see [AppConfigRepository] for the state
 * machine contract. This impl wires:
 *   - [remote] → Supabase RPC fetch (online path).
 *   - [cache]  → multiplatform-settings persistent cache (offline path).
 *
 * Initial state derives from the cache: if the cache holds a value,
 * publish [AppConfigState.Cached] immediately so the gate has a value
 * to evaluate against before [refresh] returns. Otherwise stay at
 * [AppConfigState.Loading] until the first [refresh] settles.
 *
 * Refresh failures are absorbed into the state machine, not surfaced
 * as exceptions to the caller chain (the gate is a UI surface that
 * cannot crash an entire app launch over a transient network error).
 * Returning [Result.failure] keeps the option open for diagnostic
 * paths that want to know.
 */
class AppConfigRepositoryImpl(
    /**
     * Nullable so the local-only build mode (no Supabase configured —
     * tests + local-dev without `SUPABASE_URL`) still wires the
     * repository cleanly. In local-only mode the gate just stays at
     * the initial state (Cached if a previous online run left a cache;
     * Unavailable otherwise). Either way the gate fails-open.
     */
    private val remote: RemoteAppConfigDataSource?,
    private val cache: AppConfigPreferences,
) : AppConfigRepository {
    private val _state = MutableStateFlow<AppConfigState>(initialState())

    override val state: StateFlow<AppConfigState> = _state.asStateFlow()

    override suspend fun refresh(): Result<AppConfig> {
        val remote =
            this.remote
                ?: run {
                    // Local-only mode: never reach a live state. Keep cached
                    // value if present; transition Loading → Unavailable so
                    // the gate can move past the initial spinner.
                    if (_state.value is AppConfigState.Loading) {
                        _state.value = AppConfigState.Unavailable
                    }
                    return Result.failure(IllegalStateException("Supabase not configured (local-only mode)"))
                }
        return refreshInternal(remote)
    }

    private suspend fun refreshInternal(remote: RemoteAppConfigDataSource): Result<AppConfig> =
        try {
            val fetched = remote.fetch()
            if (fetched != null) {
                cache.setCached(fetched)
                _state.value = AppConfigState.Live(fetched)
                Result.success(fetched)
            } else {
                // RPC returned zero rows. This is the migration-skipped
                // or seed-row-deleted case. Don't regress state — if we
                // had a cache or live value, keep it; if not, signal
                // Unavailable.
                if (_state.value is AppConfigState.Loading) {
                    _state.value = AppConfigState.Unavailable
                }
                Result.failure(IllegalStateException("get_app_config() returned zero rows"))
            }
        } catch (e: CancellationException) {
            // Coroutine cancellation MUST propagate so structured
            // concurrency stays correct (caller is responsible for
            // cleanup). Don't swallow it into Result.failure.
            throw e
        } catch (e: Throwable) {
            // Network / server failure. State stays at its previous
            // value (Cached if a cache hit existed at init, Unavailable
            // if first launch + offline). The state machine in
            // [AppConfigRepository] KDoc spells out the transitions.
            if (_state.value is AppConfigState.Loading) {
                _state.value = AppConfigState.Unavailable
            }
            Result.failure(e)
        }

    private fun initialState(): AppConfigState {
        val cached = cache.getCached()
        return if (cached != null) AppConfigState.Cached(cached) else AppConfigState.Loading
    }
}
