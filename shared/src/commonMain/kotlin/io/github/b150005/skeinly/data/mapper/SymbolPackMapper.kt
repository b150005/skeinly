package io.github.b150005.skeinly.data.mapper

import io.github.b150005.skeinly.db.SymbolPackEntity
import io.github.b150005.skeinly.domain.model.SymbolPack
import io.github.b150005.skeinly.domain.model.SymbolPackPayloadEntry
import io.github.b150005.skeinly.domain.model.SymbolPackPayloadParameterSlot
import io.github.b150005.skeinly.domain.model.SymbolPackTier
import io.github.b150005.skeinly.domain.symbol.ParameterSlot
import io.github.b150005.skeinly.domain.symbol.SymbolCategory
import io.github.b150005.skeinly.domain.symbol.SymbolDefinition
import kotlin.time.Instant

/**
 * Maps [SymbolPackPayloadEntry] (snake_case wire format from
 * `<pack_id>/<version>/payload.json` in the `symbol-packs` Storage bucket)
 * to and from [SymbolDefinition] (camelCase domain type used by
 * `SymbolCatalog` consumers).
 *
 * The conversion is **lossless** in both directions — a round-trip of
 * Definition → Entry → Definition produces an equal value. The only
 * extra information [SymbolPackPayloadEntry] carries beyond
 * [SymbolDefinition] is the per-symbol [SymbolPackTier]; the
 * [toDefinition] direction discards it and the [toPayloadEntry]
 * direction requires the caller to supply it (since the in-binary
 * `DefaultSymbolCatalog` has no concept of tier — it's the bundled
 * offline fallback that pre-dates the Pro pack scheme).
 *
 * Phase 41.1.4's `generateSymbolPackPayloads` Gradle task uses
 * [toPayloadEntry] to export the bundled catalog into the seed packs
 * (`jis.knit.beginner` + `jis.crochet.beginner`, both `tier = FREE`).
 * Phase 41.2's `SymbolPackSyncManager` will use [toDefinition] when
 * materializing a downloaded payload into the SQLDelight cache.
 */
internal fun SymbolPackPayloadEntry.toDefinition(): SymbolDefinition =
    SymbolDefinition(
        id = id,
        category = category.toSymbolCategory(),
        pathData = pathData,
        jaLabel = jaLabel,
        enLabel = enLabel,
        widthUnits = widthUnits,
        heightUnits = heightUnits,
        jisReference = jisReference,
        cycName = cycName,
        parameterSlots = parameterSlots.map { it.toParameterSlot() },
        jaDescription = jaDescription,
        enDescription = enDescription,
        aliases = aliases,
        fill = fill,
    )

internal fun SymbolDefinition.toPayloadEntry(tier: SymbolPackTier): SymbolPackPayloadEntry =
    SymbolPackPayloadEntry(
        id = id,
        category = category.name,
        tier = tier,
        pathData = pathData,
        fill = fill,
        widthUnits = widthUnits,
        heightUnits = heightUnits,
        parameterSlots = parameterSlots.map { it.toPayloadSlot() },
        jaLabel = jaLabel,
        enLabel = enLabel,
        jaDescription = jaDescription,
        enDescription = enDescription,
        aliases = aliases,
        jisReference = jisReference,
        cycName = cycName,
    )

internal fun SymbolPackPayloadParameterSlot.toParameterSlot(): ParameterSlot =
    ParameterSlot(
        key = key,
        x = x,
        y = y,
        defaultValue = defaultValue,
        jaLabel = jaLabel,
        enLabel = enLabel,
    )

internal fun ParameterSlot.toPayloadSlot(): SymbolPackPayloadParameterSlot =
    SymbolPackPayloadParameterSlot(
        key = key,
        x = x,
        y = y,
        defaultValue = defaultValue,
        jaLabel = jaLabel,
        enLabel = enLabel,
    )

/**
 * Resolves a [SymbolPackPayloadEntry.category] string to a
 * [SymbolCategory] enum value. Unknown values raise — the wire format
 * MUST match the closed enum exactly. Bumping [SymbolPackPayload.CURRENT_SCHEMA_VERSION]
 * is the contract for adding a new category, not silent fall-through.
 */
internal fun String.toSymbolCategory(): SymbolCategory =
    when (this) {
        SymbolCategory.KNIT.name -> SymbolCategory.KNIT
        SymbolCategory.CROCHET.name -> SymbolCategory.CROCHET
        SymbolCategory.AFGHAN.name -> SymbolCategory.AFGHAN
        SymbolCategory.MACHINE.name -> SymbolCategory.MACHINE
        else -> error("Unknown SymbolCategory wire value: '$this'")
    }

// ---------------------------------------------------------------------
// Phase 41.2b: SymbolPack ↔ SymbolPackEntity (SQLDelight) mapping.
// Catalog metadata mirror; payload bodies live in DownloadedPackPayloadEntity
// and are stored as the raw JSON `payload.json` body.
// ---------------------------------------------------------------------

internal fun SymbolPackEntity.toDomain(): SymbolPack =
    SymbolPack(
        id = id,
        tier = tier.toSymbolPackTier(),
        version = version.toInt(),
        displayName = display_name,
        description = description,
        payloadPath = payload_path,
        payloadSize = payload_size.toInt(),
        symbolCount = symbol_count.toInt(),
        signedUntil = signed_until?.let { Instant.parse(it) },
        createdAt = Instant.parse(created_at),
        updatedAt = Instant.parse(updated_at),
    )

internal fun String.toSymbolPackTier(): SymbolPackTier =
    when (this) {
        "free" -> SymbolPackTier.FREE
        "pro" -> SymbolPackTier.PRO
        // Hard fail rather than silent fall-through to FREE — an unknown tier
        // string from a future migration MUST surface loudly so we don't
        // silently grant Pro symbols to free users (or vice versa).
        else -> error("Unknown SymbolPackTier wire value: '$this'")
    }

// Exhaustiveness anchor: every member of SymbolPackTier MUST have a
// branch here AND a matching `@SerialName` on the enum. The two formats
// (db-string vs JSON wire) are kept independently in sync; the round-trip
// test in SymbolPackMapperTest catches drift, but a new enum member added
// without a matching branch here will fail compile-time exhaustiveness on
// this `when` (no `else`) — that's the contract.
internal fun SymbolPackTier.toDbString(): String =
    when (this) {
        SymbolPackTier.FREE -> "free"
        SymbolPackTier.PRO -> "pro"
    }
