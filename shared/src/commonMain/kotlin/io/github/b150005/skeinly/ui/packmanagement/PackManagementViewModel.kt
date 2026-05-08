package io.github.b150005.skeinly.ui.packmanagement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.data.sync.SyncCycleResult
import io.github.b150005.skeinly.domain.symbol.PackInventory
import io.github.b150005.skeinly.domain.symbol.PackRow
import io.github.b150005.skeinly.domain.symbol.SymbolPackCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Phase 41.4 (ADR-016 §5.2 §6 §41.4) — Settings → "Manage Symbol Packs"
 * surface. Lists every catalog pack (FREE + PRO + LOCKED-PRO) with per-row
 * status badges, total downloaded-disk-size, and a "Refresh" affordance
 * that triggers a [io.github.b150005.skeinly.data.sync.SymbolPackSyncManager.sync]
 * cycle.
 *
 * **Phase 41.5 cleanup (ADR-016 §41.5.3).** Pre-41.5 this class injected
 * [io.github.b150005.skeinly.domain.symbol.EntitlementResolver] directly
 * and computed the `Locked` status inline — a §41.5.1 violation (gate
 * decision must live at the gate site, not the call site). Phase 41.5
 * pushes that decision down into [SymbolPackCatalog] so this class is
 * now Pro-policy-agnostic: it consumes [PackInventory] verbatim and
 * renders the resolved status. A future Pro-policy refinement (per-pack
 * entitlement, trial users get partial Pro) lands in the catalog impl
 * with no change here.
 *
 * **No write actions in this slice.** ADR-016 §5.2 names a "Free up storage"
 * affordance and a per-pack "Update / Download" button but Phase 41.4
 * scopes those out — they require either an Edge Function call to flip
 * server-side `downloaded_version = 0` or a per-pack download dispatch
 * that bypasses the regular sync cycle. Refresh + display is enough to
 * close the user-visible info-loop ("which packs are on my device"). The
 * write actions land in Phase 41.6+ alongside the broader Pro-feature
 * gating story.
 *
 * **State derivation.** [PackRow] is now a derived view computed inside
 * [SymbolPackCatalog.listInventory]. [load] / [refresh] just await the
 * catalog and forward the result to state.
 *
 * **Privacy.** Subscription state is never observed here — it lives
 * inside [SymbolPackCatalog]. The screen does not display any user-
 * identifying information; the row badges are computed from pack tier
 * + on-disk presence + the gate inside the catalog.
 */
data class PackManagementState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val rows: List<PackRow> = emptyList(),
    /** Total bytes of every downloaded payload currently on disk. */
    val totalDownloadedBytes: Long = 0L,
    val error: String? = null,
)

sealed interface PackManagementEvent {
    data object Refresh : PackManagementEvent

    data object ClearError : PackManagementEvent
}

/**
 * Suspending callback the ViewModel invokes on Refresh. Lambda indirection
 * keeps the ViewModel testable without booting the full sync graph in
 * commonTest. Wired at the DI boundary as `symbolPackSyncManager::sync`
 * (with a result mapping). Nullable + default-null because the sync
 * manager is registered conditionally (only when Supabase is configured)
 * — local-only dev builds simply re-read the catalog without dispatching
 * a sync cycle.
 */
typealias PackSyncDispatch = suspend () -> SyncCycleResult

class PackManagementViewModel(
    private val symbolPackCatalog: SymbolPackCatalog,
    private val syncDispatch: PackSyncDispatch? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(PackManagementState(isLoading = true))
    val state: StateFlow<PackManagementState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    fun onEvent(event: PackManagementEvent) {
        when (event) {
            PackManagementEvent.Refresh -> viewModelScope.launch { refresh() }
            PackManagementEvent.ClearError -> _state.update { it.copy(error = null) }
        }
    }

    private suspend fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        try {
            val inventory = symbolPackCatalog.listInventory()
            _state.update {
                it.copy(
                    isLoading = false,
                    rows = inventory.rows,
                    totalDownloadedBytes = inventory.totalDownloadedBytes,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    isLoading = false,
                    error = e.message ?: "Could not load symbol packs.",
                )
            }
        }
    }

    private suspend fun refresh() {
        // Re-entrancy guard: a second Refresh tap while a cycle is in
        // flight is a no-op rather than a queued cycle. SymbolPackSyncManager
        // already serializes via tryLock + returns Skipped("sync_already_in_flight"),
        // but we want the UI's `isRefreshing` to actually flip back on the
        // first cycle's completion, not on the no-op skip's return.
        if (_state.value.isRefreshing) return
        _state.update { it.copy(isRefreshing = true, error = null) }
        try {
            val dispatch = syncDispatch
            if (dispatch != null) {
                val result = dispatch()
                if (result is SyncCycleResult.ManifestFetchFailed) {
                    val cause = result.cause
                    _state.update {
                        it.copy(error = cause.message ?: "Could not refresh symbol packs.")
                    }
                } else if (result is SyncCycleResult.ManifestPersistFailed) {
                    val cause = result.cause
                    _state.update {
                        it.copy(error = cause.message ?: "Could not refresh symbol packs.")
                    }
                }
                // Skipped + Completed (with possible per-pack failures) both
                // proceed to re-read the catalog — partial successes still
                // leave the inventory in a usable state and the user benefits
                // from seeing whatever did land.
            }
            val inventory = symbolPackCatalog.listInventory()
            _state.update {
                it.copy(
                    isRefreshing = false,
                    rows = inventory.rows,
                    totalDownloadedBytes = inventory.totalDownloadedBytes,
                )
            }
        } catch (e: CancellationException) {
            // Make sure isRefreshing flips back on cancellation so the UI
            // can recover if e.g. the user navigates away mid-sync.
            _state.update { it.copy(isRefreshing = false) }
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(
                    isRefreshing = false,
                    error = e.message ?: "Could not refresh symbol packs.",
                )
            }
        }
    }
}
