package io.github.b150005.skeinly.domain.symbol

/**
 * Y3 — Catalog locale-aware resolver consolidation.
 *
 * Returns the locale-appropriate label for a [SymbolDefinition]. Centralizes
 * the predicate `locale.startsWith("ja", ignoreCase = true)` previously
 * duplicated inline across 5 chart-UI sites (X3 surface tech-debt).
 *
 * Contract: any locale string whose lowercased form starts with `ja`
 * (e.g. `"ja"`, `"ja_JP"`, `"JA"`) returns [SymbolDefinition.jaLabel];
 * anything else (including the empty string and other languages) returns
 * [SymbolDefinition.enLabel].
 *
 * @param locale `DeviceContext.locale` style string — BCP-47 or POSIX form
 *   (`"ja"`, `"ja_JP"`, `"en_US"`, etc.). Not a Java/JDK `Locale` object so
 *   the accessor stays multiplatform without introducing a wrapper.
 */
fun SymbolDefinition.localizedLabel(locale: String): String = if (locale.startsWith("ja", ignoreCase = true)) jaLabel else enLabel

/**
 * Y3 — Catalog locale-aware resolver consolidation.
 *
 * Symmetric to [SymbolDefinition.localizedLabel]. Returns the locale-appropriate
 * category name. Currently no Kotlin call site consumes this directly, but it
 * exists for symmetry with [SymbolDefinition.localizedLabel] and as a receiver
 * for the future Swift→Kotlin port of `SymbolGalleryScreen.swift` / inline
 * `Text(category.enLabel)` sites.
 */
fun SymbolCategory.localizedLabel(locale: String): String = if (locale.startsWith("ja", ignoreCase = true)) jaLabel else enLabel
