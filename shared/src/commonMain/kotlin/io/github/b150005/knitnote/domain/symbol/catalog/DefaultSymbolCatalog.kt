package io.github.b150005.knitnote.domain.symbol.catalog

import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.symbol.SvgPathParser
import io.github.b150005.knitnote.domain.symbol.SymbolCatalog
import io.github.b150005.knitnote.domain.symbol.SymbolCategory
import io.github.b150005.knitnote.domain.symbol.SymbolDefinition

/**
 * Compile-time [SymbolCatalog] bundled with the app. Currently exposes the Phase 30
 * knitting-needle set; later phases append crochet / afghan / machine entries.
 *
 * Construction validates every entry once so packaging errors surface at app startup
 * rather than during rendering.
 */
class DefaultSymbolCatalog private constructor(
    private val byId: Map<String, SymbolDefinition>,
    private val byCategory: Map<SymbolCategory, List<SymbolDefinition>>,
    private val ordered: List<SymbolDefinition>,
) : SymbolCatalog {
    override fun get(id: String): SymbolDefinition? = byId[id]

    override fun listByCategory(category: SymbolCategory): List<SymbolDefinition> = byCategory[category].orEmpty()

    override fun all(): List<SymbolDefinition> = ordered

    companion object {
        /** Singleton bundled catalog. */
        val INSTANCE: DefaultSymbolCatalog by lazy { create(bundledDefinitions()) }

        private fun bundledDefinitions(): List<SymbolDefinition> = KnitSymbols.all + CycSymbols.all + CrochetSymbols.all

        /**
         * Builds and validates a catalog from [defs]. Visible for tests that want
         * to exercise the validation logic with synthetic inputs.
         */
        fun create(defs: List<SymbolDefinition>): DefaultSymbolCatalog {
            val seen = mutableSetOf<String>()
            val byCategory = mutableMapOf<SymbolCategory, MutableList<SymbolDefinition>>()
            for (def in defs) {
                require(StructuredChart.isValidSymbolId(def.id)) {
                    "Invalid symbol id '${def.id}' (fails SYMBOL_ID_REGEX)"
                }
                require(seen.add(def.id)) {
                    "Duplicate symbol id in catalog: '${def.id}'"
                }
                // Cheap proof that the packaged path data parses; throws if not.
                SvgPathParser.parse(def.pathData)
                byCategory.getOrPut(def.category) { mutableListOf() }.add(def)
            }
            val byId = defs.associateBy { it.id }
            val sortedByCategory =
                byCategory.mapValues { (_, list) -> list.sortedBy { it.id } }
            val ordered =
                SymbolCategory.entries.flatMap { cat -> sortedByCategory[cat].orEmpty() }
            return DefaultSymbolCatalog(
                byId = byId,
                byCategory = sortedByCategory,
                ordered = ordered,
            )
        }
    }
}
