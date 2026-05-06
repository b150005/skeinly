package io.github.b150005.skeinly.domain.symbol.catalog

import io.github.b150005.skeinly.data.mapper.toPayloadEntry
import io.github.b150005.skeinly.domain.model.SymbolPackPayload
import io.github.b150005.skeinly.domain.model.SymbolPackTier
import io.github.b150005.skeinly.domain.symbol.SymbolCategory
import io.github.b150005.skeinly.domain.symbol.SymbolDefinition

/**
 * Pure pack-assignment rules for the Phase 41.1 seed packs (ADR-016 §6).
 *
 * The bundled `DefaultSymbolCatalog` partitions cleanly by [SymbolCategory]:
 * every [SymbolCategory.KNIT] entry goes into `jis.knit.beginner` and every
 * [SymbolCategory.CROCHET] entry goes into `jis.crochet.beginner`. v1 ships
 * both as `tier = FREE` — the on-disk bundled catalog stays as offline
 * fallback and is structurally identical to what the (always-FREE) seed
 * packs deliver, so first-launch users with no network see the same
 * symbols they see today.
 *
 * Pack ids must follow the [SymbolPackId.isWellFormed] regex; the helper
 * fails fast on a malformed id so the JSON files we upload to Storage
 * never carry an unparseable pack id.
 *
 * **Symbol-id collisions**: at the parent-domain level a single
 * [SymbolDefinition.id] is unique across the bundled catalog
 * (`DefaultSymbolCatalog.create` enforces this). At the pack level the
 * partition is non-overlapping by category, so the same id never appears
 * in two seed packs. If a future advanced pack introduces a symbol with
 * the same id as one in a beginner pack (e.g. an enhanced `jis.knit.knit`
 * with extra parameter slots), [CompositeSymbolCatalog] resolves
 * "newest-version-wins across packs" per ADR-016 §4.1; that's a Phase
 * 41.2 concern, not 41.1.
 */
object SymbolPackAssignments {
    /**
     * The seed pack ids Phase 41.1 ships. Public constants so the
     * Gradle generator task and the seed-metadata SQL agree on the
     * exact id strings.
     */
    object SymbolPackId {
        const val KNIT_BEGINNER: String = "jis.knit.beginner"
        const val CROCHET_BEGINNER: String = "jis.crochet.beginner"

        private val ID_REGEX = Regex("^[a-z]+(\\.[a-z][a-z0-9-]*)+$")

        fun isWellFormed(id: String): Boolean = ID_REGEX.matches(id)
    }

    /**
     * Maps a [SymbolCategory] to the seed pack id that should contain
     * its symbols. Phase 41.1 only assigns KNIT + CROCHET; AFGHAN +
     * MACHINE return null (no seed pack today; future Phase ships them
     * as their own packs).
     */
    fun beginnerPackIdForCategory(category: SymbolCategory): String? =
        when (category) {
            SymbolCategory.KNIT -> SymbolPackId.KNIT_BEGINNER
            SymbolCategory.CROCHET -> SymbolPackId.CROCHET_BEGINNER
            SymbolCategory.AFGHAN -> null
            SymbolCategory.MACHINE -> null
        }

    /**
     * Partitions [defs] across the seed packs. Symbols whose category
     * has no assigned beginner pack ([beginnerPackIdForCategory] returns
     * null) are silently dropped — they belong to a not-yet-shipped
     * pack and should not leak into a beginner payload.
     *
     * Output is deterministic: pack ids appear in [SymbolCategory.entries]
     * iteration order; each pack's `symbols` list is sorted by id. The
     * deterministic shape lets the JSON payload round-trip byte-equal
     * across regenerations as long as the input definitions are
     * unchanged — which means the test that anchors the generated
     * payload byte-shape (or its content hash) catches accidental
     * order drift before it ships.
     */
    fun assignToBeginnerPacks(
        defs: List<SymbolDefinition>,
        version: Int,
    ): List<SymbolPackPayload> {
        require(version > 0) { "version must be positive, got $version" }

        val byPack: Map<String, List<SymbolDefinition>> =
            SymbolCategory.entries
                .mapNotNull { cat ->
                    val packId = beginnerPackIdForCategory(cat) ?: return@mapNotNull null
                    val symbolsForPack =
                        defs.filter { it.category == cat }.sortedBy { it.id }
                    if (symbolsForPack.isEmpty()) null else packId to symbolsForPack
                }.toMap()

        return byPack.map { (packId, symbols) ->
            require(SymbolPackId.isWellFormed(packId)) { "Malformed pack id: '$packId'" }
            SymbolPackPayload(
                packId = packId,
                version = version,
                schemaVersion = SymbolPackPayload.CURRENT_SCHEMA_VERSION,
                symbols = symbols.map { it.toPayloadEntry(SymbolPackTier.FREE) },
            )
        }
    }
}
