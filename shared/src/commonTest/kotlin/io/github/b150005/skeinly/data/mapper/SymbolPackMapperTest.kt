package io.github.b150005.skeinly.data.mapper

import io.github.b150005.skeinly.domain.model.SymbolPack
import io.github.b150005.skeinly.domain.model.SymbolPackPayload
import io.github.b150005.skeinly.domain.model.SymbolPackTier
import io.github.b150005.skeinly.domain.symbol.ParameterSlot
import io.github.b150005.skeinly.domain.symbol.SymbolCategory
import io.github.b150005.skeinly.domain.symbol.SymbolDefinition
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class SymbolPackMapperTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `SymbolPack json round trip preserves every field including nullables`() {
        val original =
            SymbolPack(
                id = "jis.knit.intermediate",
                tier = SymbolPackTier.PRO,
                version = 7,
                displayName = "Knit – Intermediate",
                description = "Cables and twisted-stitch glyphs.",
                payloadPath = "jis.knit.intermediate/7/payload.json",
                payloadSize = 47821,
                symbolCount = 28,
                signedUntil = Instant.parse("2026-12-31T23:59:59Z"),
                createdAt = Instant.parse("2026-05-01T00:00:00Z"),
                updatedAt = Instant.parse("2026-05-06T08:00:00Z"),
            )

        val encoded = json.encodeToString(SymbolPack.serializer(), original)
        val decoded = json.decodeFromString(SymbolPack.serializer(), encoded)

        assertEquals(original, decoded)
        // Surface evidence that the wire format keys are snake_case (regression
        // anchor against accidental @SerialName drops on a field).
        assertTrue("display_name" in encoded, "encoded JSON must use snake_case display_name")
        assertTrue("payload_path" in encoded, "encoded JSON must use snake_case payload_path")
        assertTrue("payload_size" in encoded, "encoded JSON must use snake_case payload_size")
        assertTrue("symbol_count" in encoded, "encoded JSON must use snake_case symbol_count")
        assertTrue("signed_until" in encoded, "encoded JSON must use snake_case signed_until")
    }

    @Test
    fun `SymbolPackTier wire values match the Postgres tier CHECK constraint`() {
        val freeJson = json.encodeToString(SymbolPackTier.serializer(), SymbolPackTier.FREE)
        val proJson = json.encodeToString(SymbolPackTier.serializer(), SymbolPackTier.PRO)

        // The Postgres CHECK is `tier IN ('free', 'pro')` — wire values must
        // match those bare lowercase strings exactly. Drift here breaks the
        // Edge Function entitlement gate silently.
        assertEquals("\"free\"", freeJson)
        assertEquals("\"pro\"", proJson)
    }

    @Test
    fun `SymbolPackPayload deserializes from the ADR canonical reference shape`() {
        val canonical =
            """
            {
              "pack_id": "jis.knit.intermediate",
              "version": 7,
              "schema_version": 1,
              "symbols": [
                {
                  "id": "jis.knit.cable.6st",
                  "category": "KNIT",
                  "tier": "pro",
                  "path_data": "M0,0 L8,0 L8,4 L0,4 Z",
                  "fill": false,
                  "width_units": 6,
                  "height_units": 1,
                  "parameter_slots": [],
                  "ja_label": "6目交差",
                  "en_label": "6-stitch cable"
                }
              ]
            }
            """.trimIndent()

        val payload = json.decodeFromString(SymbolPackPayload.serializer(), canonical)

        assertEquals("jis.knit.intermediate", payload.packId)
        assertEquals(7, payload.version)
        assertEquals(SymbolPackPayload.CURRENT_SCHEMA_VERSION, payload.schemaVersion)
        assertEquals(1, payload.symbols.size)

        val cable = payload.symbols.single()
        assertEquals("jis.knit.cable.6st", cable.id)
        assertEquals("KNIT", cable.category)
        assertEquals(SymbolPackTier.PRO, cable.tier)
        assertEquals(6, cable.widthUnits)
        assertEquals("6目交差", cable.jaLabel)
    }

    @Test
    fun `SymbolPackPayloadEntry survives unknown future fields without raising`() {
        // ADR-016 forward-compat contract: additive changes to
        // SymbolPackPayloadEntry MUST NOT bump CURRENT_SCHEMA_VERSION. Old
        // clients (this one) tolerate unknown fields with `ignoreUnknownKeys`.
        val futureShape =
            """
            {
              "pack_id": "jis.knit.beginner",
              "version": 1,
              "schema_version": 1,
              "future_top_level_marker": "must-be-ignored",
              "symbols": [
                {
                  "id": "jis.knit.knit",
                  "category": "KNIT",
                  "tier": "free",
                  "path_data": "M0,0 L1,0 L1,1 L0,1 Z",
                  "ja_label": "表目",
                  "en_label": "knit",
                  "future_per_symbol_marker": 42
                }
              ]
            }
            """.trimIndent()

        val payload = json.decodeFromString(SymbolPackPayload.serializer(), futureShape)

        assertEquals("jis.knit.knit", payload.symbols.single().id)
        assertEquals(SymbolPackTier.FREE, payload.symbols.single().tier)
    }

    @Test
    fun `SymbolPackPayloadEntry is lossless to and from SymbolDefinition`() {
        val original =
            SymbolDefinition(
                id = "jis.knit.cable.4st",
                category = SymbolCategory.KNIT,
                pathData = "M0,0 L4,0 L4,2 L0,2 Z",
                jaLabel = "4目交差",
                enLabel = "4-stitch cable",
                widthUnits = 4,
                heightUnits = 1,
                jisReference = "JIS L 0201-1995 表1 No.42",
                cycName = null,
                parameterSlots =
                    listOf(
                        ParameterSlot(
                            key = "n",
                            x = 0.5,
                            y = 0.5,
                            defaultValue = "4",
                            jaLabel = "目数",
                            enLabel = "stitch count",
                        ),
                    ),
                jaDescription = "4目交差ケーブル",
                enDescription = null,
                aliases = listOf("ケーブル4"),
                fill = false,
            )

        val entry = original.toPayloadEntry(SymbolPackTier.PRO)
        val roundTripped = entry.toDefinition()

        assertEquals(original, roundTripped)
        // The tier hop-through is asymmetric — toDefinition discards it because
        // SymbolDefinition has no tier concept. Verify the encode side carried it.
        assertEquals(SymbolPackTier.PRO, entry.tier)
        // Per-symbol-category wire form is the unprefixed enum name.
        assertEquals("KNIT", entry.category)
    }

    @Test
    fun `unknown SymbolCategory wire value raises rather than silently falling through`() {
        val rogueCategory = "PURL_OF_DOOM"
        val ex = assertFailsWith<IllegalStateException> { rogueCategory.toSymbolCategory() }
        // Match content-not-shape — the message should echo the offending value
        // so future readers can pinpoint a wire-format drift quickly.
        assertTrue(rogueCategory in (ex.message ?: ""), "error message should echo the bad value, was: ${ex.message}")
    }

    // ----- Phase 41.2b: SymbolPackTier db-string ↔ enum mapping ---------------

    @Test
    fun `SymbolPackTier db-string round trips for both values`() {
        assertEquals(SymbolPackTier.FREE, "free".toSymbolPackTier())
        assertEquals(SymbolPackTier.PRO, "pro".toSymbolPackTier())
        assertEquals("free", SymbolPackTier.FREE.toDbString())
        assertEquals("pro", SymbolPackTier.PRO.toDbString())
    }

    @Test
    fun `unknown SymbolPackTier wire value raises rather than silently mis-classifying`() {
        // Hard fail beats silent fall-through — an unknown tier string
        // would silently grant or revoke Pro symbols if mapped incorrectly.
        val ex = assertFailsWith<IllegalStateException> { "premium".toSymbolPackTier() }
        assertTrue("premium" in (ex.message ?: ""), "error message should echo the bad value, was: ${ex.message}")
    }
}
