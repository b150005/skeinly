package io.github.b150005.skeinly.data.remote

import io.github.b150005.skeinly.domain.model.AppConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Phase 39 (W4 / 2026-05-11) — fetches the single-row `app_config` from
 * Supabase via the `get_app_config()` RPC (see migration 028).
 *
 * The RPC is `SECURITY DEFINER` + granted to `anon` + `authenticated`,
 * so the force-update gate can read it before login (offline-first
 * contract: cache the latest value, gate startup against the cache).
 *
 * Empty-table case: the RPC returns zero rows when migration 028 was
 * skipped on a local Supabase or the seed row was manually deleted.
 * [fetch] returns null in that case so the repository can keep the
 * cached value rather than blanking it.
 */
class RemoteAppConfigDataSource(
    private val supabaseClient: SupabaseClient,
) {
    /**
     * Calls `public.get_app_config()` and decodes the single row.
     * Returns null when the RPC returns zero rows (defensive — the
     * seed row should always exist in prod; absence indicates a config
     * regression we'd rather log-and-fall-back than crash on).
     */
    suspend fun fetch(): AppConfig? {
        val result = supabaseClient.postgrest.rpc("get_app_config")
        val rows = result.decodeList<AppConfigRow>()
        val row = rows.firstOrNull() ?: return null
        return AppConfig(
            minRequiredVersionAndroid = row.minRequiredVersionAndroid,
            minRequiredVersionIos = row.minRequiredVersionIos,
            forceUpdateMessageEn = row.forceUpdateMessageEn,
            forceUpdateMessageJa = row.forceUpdateMessageJa,
            maintenanceModeActive = row.maintenanceModeActive,
            maintenanceMessageEn = row.maintenanceMessageEn,
            maintenanceMessageJa = row.maintenanceMessageJa,
        )
    }

    /**
     * Wire-shape mirroring the `get_app_config()` RPC's `RETURNS TABLE`
     * declaration. Snake_case field names match the SQL column names
     * (kotlinx-serialization's @SerialName preserves them in the JSON
     * decode); the domain [AppConfig] uses camelCase to match Kotlin
     * convention.
     *
     * Pre-alpha A15 (migration 029) extends the row with three
     * maintenance-mode columns. Defaults (false / null) keep decode
     * resilient against a pre-029 prod that returns a 4-column row.
     */
    @Serializable
    private data class AppConfigRow(
        @SerialName("min_required_version_android")
        val minRequiredVersionAndroid: String,
        @SerialName("min_required_version_ios")
        val minRequiredVersionIos: String,
        @SerialName("force_update_message_en")
        val forceUpdateMessageEn: String? = null,
        @SerialName("force_update_message_ja")
        val forceUpdateMessageJa: String? = null,
        @SerialName("maintenance_mode_active")
        val maintenanceModeActive: Boolean = false,
        @SerialName("maintenance_message_en")
        val maintenanceMessageEn: String? = null,
        @SerialName("maintenance_message_ja")
        val maintenanceMessageJa: String? = null,
    )
}
