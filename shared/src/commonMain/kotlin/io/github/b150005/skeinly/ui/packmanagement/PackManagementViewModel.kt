package io.github.b150005.skeinly.ui.packmanagement

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.b150005.skeinly.data.local.LocalSymbolPackDataSource
import io.github.b150005.skeinly.data.sync.SymbolPackSyncManager
import io.github.b150005.skeinly.data.sync.SyncCycleResult
import io.github.b150005.skeinly.domain.model.SymbolPack
import io.github.b150005.skeinly.domain.model.SymbolPackTier
import io.github.b150005.skeinly.domain.symbol.EntitlementResolver
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
 * that triggers a [SymbolPackSyncManager.sync] cycle.
 *
 * **No write actions in this slice.** ADR-016 §5.2 names a "Free up storage"
 * affordance and a per-pack "Update / Download" button but Phase 41.4
 * scopes those out — they require either an Edge Function call to flip
 * server-side `downloaded_version = 0` or a per-pack download dispatch
 * that bypasses the regular sync cycle. Refresh + display is enough to
 * close the user-visible info-loop ("which packs are on my device"). The
 * write actions land in Phase 41.5+ alongside the broader Pro-feature
 * gating story.
 *
 * **State derivation.** [PackRow] is a derived view on the local mirror
 * + the latest payload row + the current entitlement gate. [load] reads
 * everything in one suspending pass; [Refresh] kicks the sync manager
 * AND re-reads the mirror after the cycle completes so any newly-landed
 * payloads land in the visible list.
 *
 * **Privacy.** Subscription state is read once (at load + after each
 * refresh) into [isProEntitled], not collected from a Flow. The screen
 * does not display any user-identifying information; the row badges are
 * computed from pack tier + on-disk presence + the snapshot.
 */
data class PackManagementState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isProEntitled: Boolean = false,
    val rows: List<PackRow> = emptyList(),
    /** Total bytes of every downloaded payload currently on disk. */
    val totalDownloadedBytes: Long = 0L,
    val error: String? = null,
)

/**
 * One pack metadata row + its on-disk presence + the resolved entitlement
 * status. The screen renders one of these as a Card per row.
 */
data class PackRow(
    val packId: String,
    val displayName: String,
    val description: String?,
    val tier: SymbolPackTier,
    val serverVersion: Int,
    val symbolCount: Int,
    val payloadSize: Int,
    val downloadedVersion: Int?,
    val status: PackStatus,
)

/**
 * UI status for a single pack — drives the badge label and chip color
 * scheme. The fold is:
 *  - [Locked]: pack is PRO + user is not entitled. Badge "Pro — locked".
 *    Tap row → paywall.
 *  - [Downloaded]: payload is on disk and matches the server version.
 *  - [UpdateAvailable]: payload is on disk but server has a newer version.
 *  - [NotDownloaded]: pack metadata is in the mirror but no payload row.
 *    Common for packs the user has never tapped on (free or Pro both).
 */
enum class PackStatus {
    Locked,
    Downloaded,
    UpdateAvailable,
    NotDownloaded,
}

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
 * — local-only dev builds simply re-read the mirror without dispatching
 * a sync cycle.
 */
typealias PackSyncDispatch = suspend () -> SyncCycleResult

class PackManagementViewModel(
    private val localSymbolPackDataSource: LocalSymbolPackDataSource,
    private val entitlementResolver: EntitlementResolver,
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
            val snapshot = readSnapshot()
            _state.update {
                it.copy(
                    isLoading = false,
                    isProEntitled = snapshot.isProEntitled,
                    rows = snapshot.rows,
                    totalDownloadedBytes = snapshot.totalDownloadedBytes,
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
                // proceed to re-read the snapshot — partial successes still
                // leave the catalog in a usable state and the user benefits
                // from seeing whatever did land.
            }
            val snapshot = readSnapshot()
            _state.update {
                it.copy(
                    isRefreshing = false,
                    isProEntitled = snapshot.isProEntitled,
                    rows = snapshot.rows,
                    totalDownloadedBytes = snapshot.totalDownloadedBytes,
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

    private suspend fun readSnapshot(): Snapshot {
        val packs = localSymbolPackDataSource.getAllPacks()
        val payloads = localSymbolPackDataSource.getAllPayloads()
        val isProEntitled = entitlementResolver.isPro()
        val payloadsByPackId =
            payloads
                .groupBy { it.packId }
                .mapValues { (_, list) -> list.maxByOrNull { it.version } }
        val rows =
            packs
                .sortedWith(rowComparator())
                .map { pack -> rowFor(pack, payloadsByPackId[pack.id]?.version, isProEntitled) }
        // Sum payload sizes for downloaded packs only — the size column on
        // the metadata row is the wire payload size, which is what's
        // actually on disk after the SQLDelight TEXT column write.
        val totalBytes =
            packs
                .filter { payloadsByPackId.containsKey(it.id) }
                .sumOf { it.payloadSize.toLong() }
        return Snapshot(rows = rows, isProEntitled = isProEntitled, totalDownloadedBytes = totalBytes)
    }

    private fun rowFor(
        pack: SymbolPack,
        downloadedVersion: Int?,
        isProEntitled: Boolean,
    ): PackRow {
        val status =
            when {
                pack.tier == SymbolPackTier.PRO && !isProEntitled -> PackStatus.Locked
                downloadedVersion == null -> PackStatus.NotDownloaded
                downloadedVersion < pack.version -> PackStatus.UpdateAvailable
                else -> PackStatus.Downloaded
            }
        return PackRow(
            packId = pack.id,
            displayName = pack.displayName,
            description = pack.description,
            tier = pack.tier,
            serverVersion = pack.version,
            symbolCount = pack.symbolCount,
            payloadSize = pack.payloadSize,
            downloadedVersion = downloadedVersion,
            status = status,
        )
    }

    /**
     * Stable ordering: FREE packs first (sorted by id), then PRO packs
     * (sorted by id). Within each tier the user gets a deterministic
     * grouping that's easy to scan for "what do I have" vs "what could
     * I unlock". Phase 41.5+ may revisit if telemetry shows the order
     * confuses users.
     */
    private fun rowComparator(): Comparator<SymbolPack> = compareBy<SymbolPack> { tierSortKey(it.tier) }.thenBy { it.id }

    private fun tierSortKey(tier: SymbolPackTier): Int =
        when (tier) {
            SymbolPackTier.FREE -> 0
            SymbolPackTier.PRO -> 1
        }

    private data class Snapshot(
        val rows: List<PackRow>,
        val isProEntitled: Boolean,
        val totalDownloadedBytes: Long,
    )
}
