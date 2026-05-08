package io.github.b150005.skeinly.domain.symbol

import io.github.b150005.skeinly.domain.model.SymbolPackTier

/**
 * Phase 41.5+ (ADR-016 §41.5.3) — pack-level catalog surface, sibling to
 * [SymbolCatalog]. Where [SymbolCatalog] resolves individual symbols, this
 * surface enumerates packs as user-facing inventory rows for the
 * "Manage Symbol Packs" screen.
 *
 * **Why a separate interface.** [SymbolCatalog] is consumed by the
 * Compose / SwiftUI render hot path (palette + cell draw) and its `get` /
 * `listByCategory` are synchronous, microsecond-scale lookups. Inventory
 * is a slower per-screen-load read against the metadata mirror that's
 * suspending and pulls a small bounded list — semantically distinct
 * concern. Keeping them on different interfaces prevents the symbol hot
 * path from accidentally touching pack-level metadata.
 *
 * **Gate-site rule (ADR-016 §41.5.1).** Implementations that gate Pro
 * packs MUST inject [EntitlementResolver] and emit [PackStatus.Locked]
 * for any pack the user lacks entitlement to. Call sites
 * (`PackManagementViewModel`) MUST NOT inject [EntitlementResolver] or
 * read `isPro()` directly — they consume [PackInventory] verbatim and
 * render the resolved status. This makes the ViewModel a Pro-policy-
 * agnostic consumer; a future Pro-policy refinement (per-pack
 * entitlements, trial users get partial Pro, etc.) lands in the catalog
 * impl with no ViewModel change.
 *
 * **Forward-compat hook for Phase 41.6+.** A "free up storage" affordance
 * (ADR-016 §5.2) would extend this interface with a `deletePayload(packId)`
 * method that the catalog gates on `tier == FREE` (Pro packs that need
 * freeing should re-route through the paywall). The current Phase 41.5
 * cleanup ships read-only inventory only.
 */
interface SymbolPackCatalog {
    /**
     * Reads the metadata mirror + the on-disk payload set + the current
     * entitlement gate, and returns one [PackRow] per pack plus the
     * total downloaded bytes across all packs with a payload row on disk.
     *
     * Suspending: hits SQLDelight queries via the local data source's
     * IO dispatcher. Cheap relative to a sync cycle but not a render-path
     * call.
     */
    suspend fun listInventory(): PackInventory
}

/**
 * One pack-management snapshot — the rows the screen renders, plus the
 * aggregate "X MB on disk" footer label. Built by [SymbolPackCatalog.listInventory].
 */
data class PackInventory(
    val rows: List<PackRow>,
    /**
     * Total bytes of every payload row physically present on disk,
     * regardless of current entitlement.
     *
     * Note that a PRO pack the user downloaded while entitled and that
     * remained on disk after subscription lapse continues to contribute
     * to this sum, even though the row's [PackStatus] is [PackStatus.Locked].
     * The catalog never deletes payload rows on entitlement lapse —
     * Phase 41.2b's archive-preservation invariant keeps the bytes on
     * device so a re-subscribed user resumes Pro access without
     * re-downloading. Phase 41.6+ may add a "free up storage"
     * affordance that explicitly drops Locked payloads; until then the
     * sum reflects physical presence, NOT current accessibility.
     *
     * Mirrors the Phase 41.4 semantics — the only difference §41.5.6
     * introduced is that the math now lives in [DefaultSymbolPackCatalog]
     * rather than `PackManagementViewModel.readSnapshot()`.
     */
    val totalDownloadedBytes: Long,
)

/**
 * One pack metadata row + its on-disk presence + the resolved entitlement
 * status. The screen renders one of these as a Card per row.
 *
 * Lives in `domain.symbol` so [SymbolPackCatalog] returns a domain-layer
 * shape — the ViewModel forwards it to the Compose / SwiftUI screens
 * verbatim. Field set unchanged from the Phase 41.4 Pre-§41.5 location;
 * the relocation only changes which package owns the type.
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
