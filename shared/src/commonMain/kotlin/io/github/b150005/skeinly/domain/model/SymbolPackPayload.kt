package io.github.b150005.skeinly.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The JSON file uploaded to the `symbol-packs` Storage bucket at
 * `<pack_id>/<version>/payload.json` (ADR-016 §3.4).
 *
 * **Forward-compat contract** ([schemaVersion]):
 * - Adding new optional fields to [SymbolPackPayloadEntry] does NOT bump
 *   [schemaVersion]. Old clients deserialize with `ignoreUnknownKeys = true`.
 * - Removing an existing [SymbolPackPayloadEntry] field, changing its
 *   semantics, or changing the top-level shape DOES bump [schemaVersion].
 *   When [schemaVersion] is bumped, the pack must split into a new
 *   [SymbolPack.id] (per the symbol-id stability contract from ADR-009 §9)
 *   so older clients keep finding the older payload at the older id.
 *
 * **Per-symbol [SymbolPackPayloadEntry.tier]** allows mixing free + pro
 * symbols within a pack. v1 ships every entry at the parent pack's tier;
 * the field is forward-compat for a future "free pack containing some
 * individually paid symbols" arrangement that the Phase 41.2
 * `CompositeSymbolCatalog` already gates per-entry.
 */
@Serializable
data class SymbolPackPayload(
    @SerialName("pack_id") val packId: String,
    val version: Int,
    @SerialName("schema_version") val schemaVersion: Int,
    val symbols: List<SymbolPackPayloadEntry>,
) {
    init {
        require(version > 0) { "SymbolPackPayload.version must be positive, got $version for $packId" }
        require(schemaVersion > 0) {
            "SymbolPackPayload.schemaVersion must be positive, got $schemaVersion for $packId"
        }
    }

    companion object {
        /**
         * The schema version Phase 41.1 ships. Bump alongside any breaking
         * change to [SymbolPackPayloadEntry]'s field shape; do NOT bump for
         * additive-only changes.
         */
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/**
 * One symbol within a [SymbolPackPayload]. Lossless wire-format mirror of
 * `domain.symbol.SymbolDefinition` plus a per-symbol [tier] field that
 * has no parent-domain analog (it's a downloaded-catalog-only concern).
 *
 * The wire format uses snake_case via [SerialName] to match the JSON in
 * Storage. The snake_case ↔ camelCase boundary lives here (and in
 * [SymbolPackPayloadParameterSlot]); the rest of the codebase consumes
 * the camelCase form via [io.github.b150005.skeinly.data.mapper.SymbolPackMapper].
 */
@Serializable
data class SymbolPackPayloadEntry(
    val id: String,
    val category: String, // matches SymbolCategory.name; mapper validates
    val tier: SymbolPackTier,
    @SerialName("path_data") val pathData: String,
    val fill: Boolean = false,
    @SerialName("width_units") val widthUnits: Int = 1,
    @SerialName("height_units") val heightUnits: Int = 1,
    @SerialName("parameter_slots") val parameterSlots: List<SymbolPackPayloadParameterSlot> = emptyList(),
    @SerialName("ja_label") val jaLabel: String,
    @SerialName("en_label") val enLabel: String,
    @SerialName("ja_description") val jaDescription: String? = null,
    @SerialName("en_description") val enDescription: String? = null,
    val aliases: List<String> = emptyList(),
    @SerialName("jis_reference") val jisReference: String? = null,
    @SerialName("cyc_name") val cycName: String? = null,
) {
    init {
        require(widthUnits > 0) {
            "SymbolPackPayloadEntry.widthUnits must be positive, got $widthUnits for $id"
        }
        require(heightUnits > 0) {
            "SymbolPackPayloadEntry.heightUnits must be positive, got $heightUnits for $id"
        }
    }
}

/**
 * Wire-format mirror of [io.github.b150005.skeinly.domain.symbol.ParameterSlot].
 * Decoupled from the domain type so adding a non-serializable field to the
 * domain (e.g. a Compose `TextStyle` someday) does not break payload parse.
 */
@Serializable
data class SymbolPackPayloadParameterSlot(
    val key: String,
    val x: Double,
    val y: Double,
    @SerialName("default_value") val defaultValue: String? = null,
    @SerialName("ja_label") val jaLabel: String,
    @SerialName("en_label") val enLabel: String,
)
