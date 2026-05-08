package io.github.b150005.skeinly.domain.symbol

/**
 * Read-only lookup over the bundled knitting symbol set.
 *
 * Implementations must return deterministic, pre-sorted results — Phase 30 ships a
 * compile-time catalog (see `DefaultSymbolCatalog`). Later phases may introduce a
 * composite implementation that overlays user-supplied symbols on top of the JIS core.
 */
interface SymbolCatalog {
    fun get(id: String): SymbolDefinition?

    fun listByCategory(category: SymbolCategory): List<SymbolDefinition>

    fun all(): List<SymbolDefinition>

    /** True when [id] resolves to a known symbol (convenience over `get(id) != null`). */
    fun contains(id: String): Boolean = get(id) != null

    /**
     * Phase 41.4 (ADR-016 §5.2) — Pro-tier entries the current user lacks
     * the entitlement to use. Surfaced by the chart editor palette so the
     * user sees the symbols rendered with a lock badge instead of being
     * completely hidden, and a tap routes through the paywall.
     *
     * Default returns empty: implementations without a Pro tier (the bundled
     * compile-time catalog) have nothing to surface here. [CompositeSymbolCatalog]
     * overrides to return the per-category Pro entries gated by
     * [EntitlementResolver.isPro].
     *
     * The returned list is disjoint from [listByCategory] for the same
     * category: an entry is in one or the other, never both. The caller may
     * concatenate them to render a single palette strip with a visual
     * locked / unlocked split.
     */
    fun listLockedPro(category: SymbolCategory): List<SymbolDefinition> = emptyList()

    // Phase 30.2+: add `findByAlias(alias: String): SymbolDefinition?` (or a broader
    // `search(query)`) so the aliases populated on [SymbolDefinition.aliases] become
    // reachable from the dictionary UI. Current Phase 30.1-fix only populates data.
}
