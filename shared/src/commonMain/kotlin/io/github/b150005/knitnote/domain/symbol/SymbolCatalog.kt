package io.github.b150005.knitnote.domain.symbol

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

    // Phase 30.2+: add `findByAlias(alias: String): SymbolDefinition?` (or a broader
    // `search(query)`) so the aliases populated on [SymbolDefinition.aliases] become
    // reachable from the dictionary UI. Current Phase 30.1-fix only populates data.
}
