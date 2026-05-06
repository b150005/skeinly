package io.github.b150005.skeinly.data.sync

import io.github.b150005.skeinly.data.local.LocalSymbolPackDataSource
import io.github.b150005.skeinly.data.remote.SymbolPackDownloadResult
import io.github.b150005.skeinly.data.remote.SymbolPackRemoteOperations
import io.github.b150005.skeinly.domain.model.SymbolPack
import io.github.b150005.skeinly.domain.model.SymbolPackPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.Json

/**
 * Phase 41.2b (ADR-016 §4.3) — orchestrates the symbol pack sync cycle:
 *
 *   1. Fetch the `symbol_packs` manifest (RLS open-read).
 *   2. Diff against the local cache (per-pack version comparison).
 *   3. For each stale-or-missing pack, call `request-pack-download` Edge
 *      Function and write the resulting payload through to the local cache.
 *   4. UPSERT the catalog mirror (`SymbolPackEntity`).
 *
 * **Failure tolerance is the design contract.** Per ADR-016 §4.3, sync is
 * background-only and never blocks UI. Network errors silently retry on next
 * launch; 403 (`pro_entitlement_required`) silently skips the pack until a
 * Realtime push restores the subscription; 429 backs off; signed-URL TTL
 * elapsed mid-fetch surfaces as `ProEntitlementRequired` from the remote
 * data source which the orchestrator routes the same way as a fresh 403
 * (next sync cycle re-mints the URL via the same Edge Function call).
 *
 * **Idempotent on the manifest side.** [LocalSymbolPackDataSource.replaceManifest]
 * is the only catalog-metadata writer here; it diffs supplied vs cached
 * inside a single transaction, so a manifest that's identical to the cache
 * is a silent no-op (single-statement test against the supplied set).
 *
 * **Mutex-serialized.** Two concurrent [sync] calls (e.g. app foreground
 * hook racing with a post-purchase RevenueCat callback) MUST NOT execute
 * the diff path twice — the second caller would see a partially-updated
 * cache and could mis-classify a pack as "missing locally" while the first
 * caller was mid-write. The mutex makes the whole cycle atomic from the
 * caller's view; concurrent callers serialize, and a sync already in
 * flight observes [SyncState.InProgress].
 *
 * **Pack-version-regression guard** (ADR-016 §4.3 failure modes): if the
 * server-side manifest reports a strictly LOWER version than what we've
 * locally cached, we skip the download, log a warning, and keep the
 * higher-version cached payload. Reaches the user as "pack didn't update"
 * — softer than silently overwriting with stale data.
 */
class SymbolPackSyncManager(
    private val remote: SymbolPackRemoteOperations?,
    private val local: LocalSymbolPackDataSource,
    private val json: Json,
) {
    private val syncMutex = Mutex()

    private val _state = MutableStateFlow<SyncState>(SyncState.Idle)
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * Runs one sync cycle. Returns the per-pack outcomes for telemetry +
     * tests; concurrent callers see the in-flight result via [state]
     * after this returns. Result is best-effort — the caller should treat
     * a returned [SyncCycleResult.Skipped] (remote unavailable / mutex
     * still held) the same as a transient sync failure.
     */
    suspend fun sync(): SyncCycleResult {
        val r = remote ?: return SyncCycleResult.Skipped(reason = "remote_unavailable")

        // tryLock: a sync already in flight returns immediately rather than
        // queueing — sync cycles are background-tolerant; chasing the tail
        // of a prior cycle adds latency without meaningful benefit.
        if (!syncMutex.tryLock()) {
            return SyncCycleResult.Skipped(reason = "sync_already_in_flight")
        }
        try {
            return runSync(r)
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun runSync(r: SymbolPackRemoteOperations): SyncCycleResult {
        _state.value = SyncState.InProgress

        val manifest =
            try {
                r.fetchManifest()
            } catch (e: CancellationException) {
                _state.value = SyncState.Idle
                throw e
            } catch (e: Exception) {
                _state.value = SyncState.Failed(e)
                return SyncCycleResult.ManifestFetchFailed(e)
            }

        try {
            local.replaceManifest(manifest)
        } catch (e: CancellationException) {
            _state.value = SyncState.Idle
            throw e
        } catch (e: Exception) {
            // Cache write failed — surface but don't try to recover. Next
            // launch retries the whole cycle; manifest fetch is cheap.
            _state.value = SyncState.Failed(e)
            return SyncCycleResult.ManifestPersistFailed(e)
        }

        val outcomes = mutableListOf<PackSyncOutcome>()
        for (pack in manifest) {
            val outcome =
                try {
                    syncOnePack(r, pack)
                } catch (e: CancellationException) {
                    _state.value = SyncState.Idle
                    throw e
                } catch (e: Exception) {
                    PackSyncOutcome.Failed(pack.id, e)
                }
            outcomes.add(outcome)
        }

        // Emit a differentiated terminal state: any per-pack hard failure
        // (network / parse / unknown / failed) flips the cycle to
        // PartiallyFailed so a future Settings → Symbol Packs "Syncing…"
        // observer can surface a "some packs failed" indicator without
        // re-walking the outcomes list. Soft skips (entitlement / 404 /
        // rate-limit / auth / version-regression) are part of normal
        // sync semantics and do NOT flip the state.
        _state.value =
            if (outcomes.any { it.isHardFailure() }) {
                SyncState.PartiallyFailed
            } else {
                SyncState.Idle
            }
        return SyncCycleResult.Completed(outcomes)
    }

    private fun PackSyncOutcome.isHardFailure(): Boolean =
        when (this) {
            is PackSyncOutcome.NetworkError,
            is PackSyncOutcome.ParseError,
            is PackSyncOutcome.UnknownError,
            is PackSyncOutcome.Failed,
            -> true
            is PackSyncOutcome.AlreadyUpToDate,
            is PackSyncOutcome.Downloaded,
            is PackSyncOutcome.SkippedProEntitlement,
            is PackSyncOutcome.SkippedPackNotFound,
            is PackSyncOutcome.SkippedRateLimited,
            is PackSyncOutcome.SkippedUnauthenticated,
            is PackSyncOutcome.VersionRegression,
            -> false
        }

    private suspend fun syncOnePack(
        r: SymbolPackRemoteOperations,
        pack: SymbolPack,
    ): PackSyncOutcome {
        val cached = local.getLatestPayload(pack.id)
        val cachedVersion = cached?.version

        // Already up-to-date — nothing to do.
        if (cachedVersion != null && cachedVersion == pack.version) {
            return PackSyncOutcome.AlreadyUpToDate(pack.id, pack.version)
        }

        // Pack version regression guard: server reports a strictly LOWER
        // version than what we locally cached. Per ADR-016 §4.3, never
        // legitimate; surface as a warning + keep the higher-version cache.
        if (cachedVersion != null && pack.version < cachedVersion) {
            return PackSyncOutcome.VersionRegression(
                packId = pack.id,
                cachedVersion = cachedVersion,
                serverVersion = pack.version,
            )
        }

        return when (val result = r.requestDownload(pack.id)) {
            is SymbolPackDownloadResult.Success -> {
                local.upsertPayload(pack.id, result.version, jsonToString(result))
                PackSyncOutcome.Downloaded(packId = pack.id, version = result.version)
            }
            is SymbolPackDownloadResult.Failure -> PackSyncOutcome.from(pack.id, result)
        }
    }

    /**
     * Re-encodes the payload to its canonical JSON representation for
     * storage. The data source decoded the wire body into [SymbolPackPayload];
     * the SQLDelight column holds the same JSON string. Round-tripping
     * normalizes whitespace + key order, which keeps the local representation
     * stable across server-side response formatter changes.
     */
    private fun jsonToString(success: SymbolPackDownloadResult.Success): String =
        json.encodeToString(SymbolPackPayload.serializer(), success.payload)
}

/**
 * Status of the sync manager — surfaced as a [StateFlow] so a future Settings
 * → Symbol Packs screen can render a "Syncing…" indicator without polling.
 *
 * State semantics:
 * - [Idle]: no cycle in flight; the most recent cycle either succeeded
 *   cleanly OR completed with only soft-skip outcomes (entitlement / 404 /
 *   rate-limit / auth / version-regression — sync routes that next cycle
 *   will retry naturally).
 * - [InProgress]: a cycle is mid-flight.
 * - [Failed]: the entire cycle failed at the manifest stage. No per-pack
 *   downloads ran.
 * - [PartiallyFailed]: the manifest landed but at least one pack hit a
 *   hard error (network / parse / unknown / unforeseen). Other packs may
 *   have downloaded successfully. The Settings screen surfaces a "some
 *   packs failed" affordance; sync will retry the failed packs next cycle.
 */
sealed interface SyncState {
    data object Idle : SyncState

    data object InProgress : SyncState

    data class Failed(
        val cause: Throwable,
    ) : SyncState

    data object PartiallyFailed : SyncState
}

/**
 * Per-pack outcome of one sync cycle. Folded into [SyncCycleResult.Completed]
 * for telemetry + test assertion.
 */
sealed interface PackSyncOutcome {
    val packId: String

    data class Downloaded(
        override val packId: String,
        val version: Int,
    ) : PackSyncOutcome

    data class AlreadyUpToDate(
        override val packId: String,
        val version: Int,
    ) : PackSyncOutcome

    data class SkippedProEntitlement(
        override val packId: String,
    ) : PackSyncOutcome

    data class SkippedPackNotFound(
        override val packId: String,
    ) : PackSyncOutcome

    data class SkippedRateLimited(
        override val packId: String,
        val retryAfterSeconds: Int,
    ) : PackSyncOutcome

    data class SkippedUnauthenticated(
        override val packId: String,
    ) : PackSyncOutcome

    data class NetworkError(
        override val packId: String,
        val cause: Throwable,
    ) : PackSyncOutcome

    data class ParseError(
        override val packId: String,
        val cause: Throwable,
    ) : PackSyncOutcome

    /**
     * Server-side manifest reports a strictly lower version than what we
     * locally cached. Never legitimate per ADR-016 §4.3; we keep the higher
     * cached payload and surface this for Sentry visibility.
     */
    data class VersionRegression(
        override val packId: String,
        val cachedVersion: Int,
        val serverVersion: Int,
    ) : PackSyncOutcome

    data class UnknownError(
        override val packId: String,
        val statusCode: Int,
        val body: String?,
    ) : PackSyncOutcome

    /**
     * Local cache write failed (SQLDelight transaction rollback,
     * unforeseen runtime exception). Surface for telemetry; sync continues
     * with the next pack rather than failing the entire cycle.
     */
    data class Failed(
        override val packId: String,
        val cause: Throwable,
    ) : PackSyncOutcome

    companion object {
        internal fun from(
            packId: String,
            failure: SymbolPackDownloadResult.Failure,
        ): PackSyncOutcome =
            when (failure) {
                is SymbolPackDownloadResult.Failure.Unauthenticated -> SkippedUnauthenticated(packId)
                is SymbolPackDownloadResult.Failure.ProEntitlementRequired -> SkippedProEntitlement(packId)
                is SymbolPackDownloadResult.Failure.PackNotFound -> SkippedPackNotFound(packId)
                is SymbolPackDownloadResult.Failure.RateLimited -> SkippedRateLimited(packId, failure.retryAfterSeconds)
                is SymbolPackDownloadResult.Failure.Network -> NetworkError(packId, failure.cause)
                is SymbolPackDownloadResult.Failure.Parse -> ParseError(packId, failure.cause)
                is SymbolPackDownloadResult.Failure.Unknown -> UnknownError(packId, failure.statusCode, failure.body)
            }
    }
}

/**
 * One sync cycle's overall outcome. [Completed] always produces a per-pack
 * [PackSyncOutcome] list (possibly empty if the manifest itself was empty).
 */
sealed interface SyncCycleResult {
    data class Skipped(
        val reason: String,
    ) : SyncCycleResult

    data class ManifestFetchFailed(
        val cause: Throwable,
    ) : SyncCycleResult

    data class ManifestPersistFailed(
        val cause: Throwable,
    ) : SyncCycleResult

    data class Completed(
        val outcomes: List<PackSyncOutcome>,
    ) : SyncCycleResult
}
