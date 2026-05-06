package io.github.b150005.skeinly.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Tier of a symbol pack — the entitlement gate that the
 * `request-pack-download` Edge Function enforces (ADR-016 §3.1, §3.3).
 *
 * Wire format uses lowercase string per the Postgres CHECK constraint in
 * migration 020 (`tier IN ('free', 'pro')`).
 *
 * - [FREE]: any authenticated caller may download (modulo the Edge
 *   Function's per-user rate cap).
 * - [PRO]: caller must additionally hold an active row in
 *   `subscriptions` (`status IN ('active','in_grace_period')` and
 *   `expires_at IS NULL OR expires_at > now()`).
 */
@Serializable
enum class SymbolPackTier {
    @SerialName("free")
    FREE,

    @SerialName("pro")
    PRO,
}

/**
 * Pack-version metadata row mirroring `public.symbol_packs` (ADR-016 §3.1).
 *
 * **Cardinality + retention.** ADR-016 §10 Q1 (resolved 2026-05-06): every
 * historical [version] is retained indefinitely until `SUM(payloadSize)`
 * across the bucket crosses 100 MB. The mirror table is the single source
 * of truth for "what versions of what packs exist server-side"; the client
 * `DownloadedPackStore` (Phase 41.2 SQLDelight schema) carries only the
 * versions a particular user has materialized locally.
 *
 * **Open-read RLS.** [SymbolPackTier.PRO] pack metadata is world-readable
 * by design — paywall preview UI lists Pro packs to non-subscribers so the
 * "Subscribe to unlock" entry-point shows a concrete pack list rather than
 * a generic pitch. Only the *payload* is gated, not the metadata.
 *
 * **Wire format.** Field names match the Postgres column names verbatim
 * via [SerialName] so the same data class round-trips through the
 * Supabase REST `select=*` response without an intermediate DTO.
 */
@Serializable
data class SymbolPack(
    val id: String,
    val tier: SymbolPackTier,
    val version: Int,
    @SerialName("display_name") val displayName: String,
    val description: String? = null,
    @SerialName("payload_path") val payloadPath: String,
    @SerialName("payload_size") val payloadSize: Int,
    @SerialName("symbol_count") val symbolCount: Int,
    @SerialName("signed_until") val signedUntil: Instant? = null,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
) {
    init {
        require(version > 0) { "SymbolPack.version must be positive, got $version for $id" }
        require(payloadSize >= 0) { "SymbolPack.payloadSize must be non-negative, got $payloadSize for $id" }
        require(symbolCount >= 0) { "SymbolPack.symbolCount must be non-negative, got $symbolCount for $id" }
    }
}

/**
 * Locale-specific override for [SymbolPack.displayName] / [SymbolPack.description]
 * mirroring `public.symbol_pack_locales` (ADR-016 §3.1).
 *
 * The parent [SymbolPack] row carries the en fallback. A locale row with
 * an empty [description] simply means "use the same en-fallback description"
 * — distinguish "no localized description" (omit the field, keep the en
 * fallback) from "deliberately no description in this locale" (set
 * [description] to empty string only when that's the editorial intent).
 */
@Serializable
data class SymbolPackLocale(
    @SerialName("pack_id") val packId: String,
    val locale: String,
    @SerialName("display_name") val displayName: String,
    val description: String? = null,
) {
    init {
        require(LOCALE_REGEX.matches(locale)) {
            "SymbolPackLocale.locale must match BCP 47 short form ([a-z]{2}(-[A-Z]{2})?), got '$locale'"
        }
    }

    companion object {
        private val LOCALE_REGEX = Regex("^[a-z]{2}(-[A-Z]{2})?$")
    }
}
