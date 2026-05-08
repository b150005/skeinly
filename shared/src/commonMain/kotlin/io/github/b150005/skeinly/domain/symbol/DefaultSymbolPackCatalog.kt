package io.github.b150005.skeinly.domain.symbol

import io.github.b150005.skeinly.data.local.LocalSymbolPackDataSource
import io.github.b150005.skeinly.domain.model.SymbolPack
import io.github.b150005.skeinly.domain.model.SymbolPackTier

/**
 * Phase 41.5+ (ADR-016 §41.5.3) — production [SymbolPackCatalog] backed
 * by the local pack-metadata mirror + the downloaded-payload table from
 * Phase 41.2b, with the Pro entitlement gate from Phase 41.2a applied
 * inline.
 *
 * **Gate-site responsibility (ADR-016 §41.5.1).** This is the file that
 * reads [EntitlementResolver.isPro] for the pack-management surface.
 * Every consumer of [SymbolPackCatalog] gets a [PackStatus] already
 * resolved against the user's current entitlement; they MUST NOT inject
 * [EntitlementResolver] themselves. A future Pro-policy refinement
 * (trial users get partial Pro, per-pack entitlements, grace-period
 * extension) lands here with zero call-site change.
 *
 * **Status derivation.** Per ADR-016 §5.2 + the Phase 41.4 precedent:
 *  - PRO pack + user not entitled → [PackStatus.Locked]
 *  - no payload row on disk → [PackStatus.NotDownloaded]
 *  - payload version < server version → [PackStatus.UpdateAvailable]
 *  - else → [PackStatus.Downloaded]
 *
 * Note that a PRO pack the user IS entitled to falls through the
 * `Locked` branch and proceeds through the same downloaded-version
 * compare as a FREE pack — the entitlement gate is the only difference
 * in the fold.
 *
 * **Stable ordering.** FREE packs first (alpha by id), then PRO packs
 * (alpha by id). Identical to the Phase 41.4 ViewModel ordering — the
 * ordering moved with the gate. Phase 41.6+ may revisit if telemetry
 * shows the order confuses users (e.g. recently-updated-pack-first).
 *
 * **Total bytes.** Sums [SymbolPack.payloadSize] across packs with a
 * payload row on disk. The metadata column is the *wire* payload size
 * in bytes — what's actually stored on disk after the SQLDelight TEXT
 * column write. Same semantics as Phase 41.4.
 */
class DefaultSymbolPackCatalog(
    private val localSymbolPackDataSource: LocalSymbolPackDataSource,
    private val entitlementResolver: EntitlementResolver,
) : SymbolPackCatalog {
    // Hoisted to a property to avoid allocating a fresh Comparator on
    // every listInventory() call (the catalog is a Koin `single` so the
    // comparator is a constant for the process lifetime).
    private val rowComparator: Comparator<SymbolPack> =
        compareBy<SymbolPack> { tierSortKey(it.tier) }.thenBy { it.id }

    override suspend fun listInventory(): PackInventory {
        val packs = localSymbolPackDataSource.getAllPacks()
        val payloads = localSymbolPackDataSource.getAllPayloads()
        // Snapshot the entitlement once per call so every row in the
        // returned list reflects a coherent view of the gate, even under
        // a concurrent state flip mid-call.
        val isUserPro = entitlementResolver.isPro()
        val downloadedVersionByPackId =
            payloads
                .groupBy { it.packId }
                .mapValues { (_, list) -> list.maxByOrNull { it.version }?.version }

        val rows =
            packs
                .sortedWith(rowComparator)
                .map { pack -> rowFor(pack, downloadedVersionByPackId[pack.id], isUserPro) }

        // Sum payload sizes for downloaded packs only — the size column
        // on the metadata row is the wire payload size, which is what's
        // actually on disk after the SQLDelight TEXT column write.
        val totalBytes =
            packs
                .filter { downloadedVersionByPackId.containsKey(it.id) }
                .sumOf { it.payloadSize.toLong() }

        return PackInventory(rows = rows, totalDownloadedBytes = totalBytes)
    }

    private fun rowFor(
        pack: SymbolPack,
        downloadedVersion: Int?,
        isUserPro: Boolean,
    ): PackRow {
        val status =
            when {
                pack.tier == SymbolPackTier.PRO && !isUserPro -> PackStatus.Locked
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

    private fun tierSortKey(tier: SymbolPackTier): Int =
        when (tier) {
            SymbolPackTier.FREE -> 0
            SymbolPackTier.PRO -> 1
        }
}
