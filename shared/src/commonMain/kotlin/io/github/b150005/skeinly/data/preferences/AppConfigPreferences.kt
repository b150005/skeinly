package io.github.b150005.skeinly.data.preferences

import com.russhwolf.settings.Settings
import io.github.b150005.skeinly.domain.model.AppConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Phase 39 (W4 / 2026-05-11) — local cache of the last successfully
 * fetched [AppConfig]. Backed by [Settings] (SharedPreferences on
 * Android, NSUserDefaults on iOS) so the force-update gate has a
 * value to compare against on offline launch.
 *
 * Single-row serialization: store the full AppConfig as a single
 * JSON string. Three reasons over per-field key/value:
 *   - Atomic update: one write commits all fields, eliminating the
 *     partial-cache race where Android / iOS min versions disagree.
 *   - Schema evolution: adding a field to [AppConfig] doesn't require
 *     a Settings key migration; absent fields default to null.
 *   - Inspectable: a single `app_config_json` Settings entry is trivial
 *     to dump for diagnostics.
 *
 * Empty / corrupt cache returns null — the gate fails-open per the
 * offline-first contract documented in [AppConfig.evaluate].
 */
interface AppConfigPreferences {
    /**
     * Returns the most recently cached config, or null when no cache
     * exists (first launch) or the cached JSON is malformed (e.g. a
     * model rename rendered older cache unreadable — fail-open).
     */
    fun getCached(): AppConfig?

    /**
     * Atomically replaces the cached config with [config]. Subsequent
     * [getCached] calls return the new value.
     */
    fun setCached(config: AppConfig)

    /** Clears the cache. Used by tests + the "Sign out" flow if scope expands. */
    fun clearCache()
}

internal class AppConfigPreferencesImpl(
    private val settings: Settings,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AppConfigPreferences {
    override fun getCached(): AppConfig? {
        val raw = settings.getStringOrNull(KEY_APP_CONFIG_JSON) ?: return null
        return try {
            val cached = json.decodeFromString<CachedAppConfig>(raw)
            cached.toDomain()
        } catch (e: SerializationException) {
            // Stale-shape cache (e.g. an AppConfig field rename) — drop
            // it so the next refresh writes the current shape. Returning
            // null here is the fail-open path: gate stays open until
            // the next online refresh.
            settings.remove(KEY_APP_CONFIG_JSON)
            null
        }
    }

    override fun setCached(config: AppConfig) {
        val cached = CachedAppConfig.fromDomain(config)
        settings.putString(KEY_APP_CONFIG_JSON, json.encodeToString(cached))
    }

    override fun clearCache() {
        settings.remove(KEY_APP_CONFIG_JSON)
    }

    /**
     * Wire-shape for the cached JSON. Kept distinct from [AppConfig]
     * (the domain model) so a future field rename in the domain can
     * be migrated through an explicit [toDomain] / [fromDomain] step
     * rather than relying on field-name coincidence.
     */
    @Serializable
    private data class CachedAppConfig(
        val minRequiredVersionAndroid: String,
        val minRequiredVersionIos: String,
        val forceUpdateMessageEn: String? = null,
        val forceUpdateMessageJa: String? = null,
        // Pre-alpha A15 — maintenance-mode columns (migration 029). Default
        // false / null so older cached payloads (pre-A15) decode cleanly:
        // `ignoreUnknownKeys = true` skips unknown keys forward, but
        // missing-key reads use these field defaults so the cache shape
        // is forward-compatible (older cache → new domain ⇒ maintenance off).
        val maintenanceModeActive: Boolean = false,
        val maintenanceMessageEn: String? = null,
        val maintenanceMessageJa: String? = null,
    ) {
        fun toDomain(): AppConfig =
            AppConfig(
                minRequiredVersionAndroid = minRequiredVersionAndroid,
                minRequiredVersionIos = minRequiredVersionIos,
                forceUpdateMessageEn = forceUpdateMessageEn,
                forceUpdateMessageJa = forceUpdateMessageJa,
                maintenanceModeActive = maintenanceModeActive,
                maintenanceMessageEn = maintenanceMessageEn,
                maintenanceMessageJa = maintenanceMessageJa,
            )

        companion object {
            fun fromDomain(config: AppConfig): CachedAppConfig =
                CachedAppConfig(
                    minRequiredVersionAndroid = config.minRequiredVersionAndroid,
                    minRequiredVersionIos = config.minRequiredVersionIos,
                    forceUpdateMessageEn = config.forceUpdateMessageEn,
                    forceUpdateMessageJa = config.forceUpdateMessageJa,
                    maintenanceModeActive = config.maintenanceModeActive,
                    maintenanceMessageEn = config.maintenanceMessageEn,
                    maintenanceMessageJa = config.maintenanceMessageJa,
                )
        }
    }

    private companion object {
        const val KEY_APP_CONFIG_JSON = "app_config_json"
    }
}
