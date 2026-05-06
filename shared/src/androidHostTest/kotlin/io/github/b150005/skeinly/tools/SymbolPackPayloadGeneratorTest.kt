package io.github.b150005.skeinly.tools

import io.github.b150005.skeinly.domain.model.SymbolPackPayload
import io.github.b150005.skeinly.domain.symbol.catalog.DefaultSymbolCatalog
import io.github.b150005.skeinly.domain.symbol.catalog.SymbolPackAssignments
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Anchor + generator for the seed pack payloads (ADR-016 §6 41.1.4).
 *
 * **Runs every CI** as a parity assertion: takes the live
 * [DefaultSymbolCatalog.INSTANCE] symbols, partitions them via
 * [SymbolPackAssignments.assignToBeginnerPacks], and asserts that the
 * resulting packs match the invariants the seed-metadata SQL relies on
 * (pack ids, FREE tier, non-empty symbol counts). A change to the
 * bundled catalog that breaks pack assignment surfaces here at CI time
 * rather than at upload time.
 *
 * **Optional file emission**: when system property
 * `skeinly.payloads.outputDir` is set, also writes each pack's
 * canonical JSON to `<outputDir>/<pack_id>/<version>/payload.json`,
 * prints a seed-metadata SQL block to stdout, and emits a manifest
 * line per pack with the byte length of the JSON file (the
 * `payload_size` value the metadata SQL needs). The generator side
 * runs only on demand — the Gradle task `generateSymbolPackPayloads`
 * is the canonical entry point, but a developer can also invoke
 * `:shared:testAndroidHostTest --tests "...generatePayloadsAndSeedSql"
 * -Dskeinly.payloads.outputDir=...` directly.
 *
 * The generator side is JVM-only because it does file I/O and stdout
 * prints; living under [androidHostTest] is the cleanest way to share
 * `DefaultSymbolCatalog` access without creating a dedicated `tools`
 * source set or wiring `JavaExec` against an AAR.
 */
class SymbolPackPayloadGeneratorTest {
    private val json =
        Json {
            // Match the Storage upload format: human-readable, deterministic key
            // order, no enclosing whitespace beyond newlines + 2-space indent.
            prettyPrint = true
            prettyPrintIndent = "  "
            // Generated payloads are authoritative, so allow no surprises.
            ignoreUnknownKeys = false
        }

    /**
     * Pack version Phase 41.1 ships. Bump alongside any change to the
     * bundled catalog that should produce new payload files. The seed
     * metadata INSERT in 41.1.4 references this same value.
     */
    private val seedPackVersion: Int = 1

    @Test
    fun generatePayloadsAndSeedSql() {
        val payloads =
            SymbolPackAssignments.assignToBeginnerPacks(
                defs = DefaultSymbolCatalog.INSTANCE.all(),
                version = seedPackVersion,
            )

        // Parity assertions — these fail if the bundled catalog drifts in
        // a way that breaks the seed pack invariants.
        val packIds = payloads.map { it.packId }.toSet()
        assertEquals(
            setOf(
                SymbolPackAssignments.SymbolPackId.KNIT_BEGINNER,
                SymbolPackAssignments.SymbolPackId.CROCHET_BEGINNER,
            ),
            packIds,
            "Bundled catalog must produce both seed packs (no AFGHAN/MACHINE today).",
        )
        payloads.forEach { payload ->
            assertTrue(payload.symbols.isNotEmpty(), "Pack ${payload.packId} must not be empty")
            assertEquals(
                seedPackVersion,
                payload.version,
                "Pack ${payload.packId} version must match seed version",
            )
            assertEquals(
                SymbolPackPayload.CURRENT_SCHEMA_VERSION,
                payload.schemaVersion,
                "Pack ${payload.packId} schema version must match payload constant",
            )
        }

        // Generator side — only runs when explicitly requested.
        val outputDir = System.getProperty("skeinly.payloads.outputDir") ?: return
        val outputRoot = File(outputDir).absoluteFile
        outputRoot.mkdirs()

        // Emit the JSON files. Path layout matches `symbol_packs.payload_path`
        // exactly so a `gsutil cp` / Supabase Dashboard upload directly
        // matches the column value.
        val seedRows =
            payloads.map { payload ->
                val packDir = File(outputRoot, "${payload.packId}/${payload.version}")
                packDir.mkdirs()
                val payloadFile = File(packDir, "payload.json")
                val encoded = json.encodeToString(SymbolPackPayload.serializer(), payload)
                payloadFile.writeText(encoded)
                SeedRow(
                    packId = payload.packId,
                    version = payload.version,
                    payloadPath = "${payload.packId}/${payload.version}/payload.json",
                    payloadSize = payloadFile.length().toInt(),
                    symbolCount = payload.symbols.size,
                )
            }

        val manifestLines =
            seedRows.joinToString("\n") {
                "MANIFEST ${it.packId} v${it.version}: ${it.payloadSize} bytes, ${it.symbolCount} symbols → ${it.payloadPath}"
            }
        println("[SymbolPackPayloadGenerator] wrote ${seedRows.size} payloads to ${outputRoot.path}")
        println(manifestLines)
        println("[SymbolPackPayloadGenerator] seed metadata SQL:")
        println(buildSeedSql(seedRows))
    }

    private data class SeedRow(
        val packId: String,
        val version: Int,
        val payloadPath: String,
        val payloadSize: Int,
        val symbolCount: Int,
    )

    private fun buildSeedSql(rows: List<SeedRow>): String =
        buildString {
            appendLine("-- Phase 41.1.4: seed pack metadata. Apply via mcp__supabase__apply_migration.")
            appendLine("-- Display names match the en-fallback contract from ADR-016 §3.1; locale-specific")
            appendLine("-- ja overrides land in symbol_pack_locales below.")
            appendLine()
            appendLine("INSERT INTO public.symbol_packs")
            appendLine("    (id, tier, version, display_name, description, payload_path, payload_size, symbol_count)")
            appendLine("VALUES")
            rows.forEachIndexed { i, row ->
                val displayName =
                    when (row.packId) {
                        SymbolPackAssignments.SymbolPackId.KNIT_BEGINNER -> "Knit – Beginner"
                        SymbolPackAssignments.SymbolPackId.CROCHET_BEGINNER -> "Crochet – Beginner"
                        else -> row.packId // defensive — a future pack should add its display name above
                    }
                val description =
                    when (row.packId) {
                        SymbolPackAssignments.SymbolPackId.KNIT_BEGINNER -> "Bundled JIS knitting symbols (free tier)."
                        SymbolPackAssignments.SymbolPackId.CROCHET_BEGINNER -> "Bundled JIS crochet symbols (free tier)."
                        else -> ""
                    }
                val sep = if (i == rows.lastIndex) ";" else ","
                append(
                    "    ('${row.packId}', 'free', ${row.version}, '$displayName', '$description', " +
                        "'${row.payloadPath}', ${row.payloadSize}, ${row.symbolCount})$sep",
                )
                appendLine()
            }
            appendLine()
            appendLine("INSERT INTO public.symbol_pack_locales (pack_id, locale, display_name, description)")
            appendLine("VALUES")
            rows.forEachIndexed { i, row ->
                val ja =
                    when (row.packId) {
                        SymbolPackAssignments.SymbolPackId.KNIT_BEGINNER ->
                            Pair("棒針編目 – ベーシック", "JIS L 0201-1995 棒針編目シンボル (無料)")
                        SymbolPackAssignments.SymbolPackId.CROCHET_BEGINNER ->
                            Pair("かぎ針編目 – ベーシック", "JIS L 0201-1995 かぎ針編目シンボル (無料)")
                        else -> Pair(row.packId, "")
                    }
                val sep = if (i == rows.lastIndex) ";" else ","
                append("    ('${row.packId}', 'ja', '${ja.first}', '${ja.second}')$sep")
                appendLine()
            }
        }
}
