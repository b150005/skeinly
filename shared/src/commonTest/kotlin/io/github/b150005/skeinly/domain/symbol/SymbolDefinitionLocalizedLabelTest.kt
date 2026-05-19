package io.github.b150005.skeinly.domain.symbol

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Y3 — Catalog locale-aware resolver consolidation.
 *
 * Verifies `SymbolDefinition.localizedLabel(locale)` and
 * `SymbolCategory.localizedLabel(locale)` extensions consolidate the inline
 * locale-ternary previously duplicated across 5 Kotlin chart-UI sites
 * (`if (deviceContext.locale.startsWith("ja", ignoreCase = true)) jaLabel else enLabel`).
 *
 * Predicate contract: `locale.startsWith("ja", ignoreCase = true)` → `jaLabel`,
 * everything else (including empty string and other languages) → `enLabel`.
 */
class SymbolDefinitionLocalizedLabelTest {
    private val definition =
        SymbolDefinition(
            id = "y3.test.symbol",
            category = SymbolCategory.KNIT,
            pathData = "M0 0 L1 1",
            jaLabel = "ja-label",
            enLabel = "en-label",
        )

    @Test
    fun `localizedLabel returns jaLabel for locale 'ja'`() {
        assertEquals("ja-label", definition.localizedLabel("ja"))
    }

    @Test
    fun `localizedLabel returns jaLabel for locale 'ja_JP' prefix match`() {
        assertEquals("ja-label", definition.localizedLabel("ja_JP"))
    }

    @Test
    fun `localizedLabel returns jaLabel for locale 'JA' case insensitive`() {
        assertEquals("ja-label", definition.localizedLabel("JA"))
    }

    @Test
    fun `localizedLabel returns enLabel for locale 'en'`() {
        assertEquals("en-label", definition.localizedLabel("en"))
    }

    @Test
    fun `localizedLabel returns enLabel for locale 'en_US'`() {
        assertEquals("en-label", definition.localizedLabel("en_US"))
    }

    @Test
    fun `localizedLabel returns enLabel for unknown locale 'fr'`() {
        assertEquals("en-label", definition.localizedLabel("fr"))
    }

    @Test
    fun `localizedLabel returns enLabel for empty locale string`() {
        assertEquals("en-label", definition.localizedLabel(""))
    }

    @Test
    fun `SymbolCategory localizedLabel returns jaLabel for locale 'ja'`() {
        assertEquals("棒針編目", SymbolCategory.KNIT.localizedLabel("ja"))
    }

    @Test
    fun `SymbolCategory localizedLabel returns jaLabel for locale 'ja_JP'`() {
        assertEquals("棒針編目", SymbolCategory.KNIT.localizedLabel("ja_JP"))
    }

    @Test
    fun `SymbolCategory localizedLabel returns jaLabel for locale 'JA' case insensitive`() {
        assertEquals("棒針編目", SymbolCategory.KNIT.localizedLabel("JA"))
    }

    @Test
    fun `SymbolCategory localizedLabel returns enLabel for locale 'en'`() {
        assertEquals("Knitting needle", SymbolCategory.KNIT.localizedLabel("en"))
    }

    @Test
    fun `SymbolCategory localizedLabel returns enLabel for locale 'en_US'`() {
        assertEquals("Knitting needle", SymbolCategory.KNIT.localizedLabel("en_US"))
    }

    @Test
    fun `SymbolCategory localizedLabel returns enLabel for unknown locale 'fr'`() {
        assertEquals("Knitting needle", SymbolCategory.KNIT.localizedLabel("fr"))
    }

    @Test
    fun `SymbolCategory localizedLabel returns enLabel for empty locale string`() {
        assertEquals("Knitting needle", SymbolCategory.KNIT.localizedLabel(""))
    }
}
