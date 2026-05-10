package io.github.b150005.skeinly.data.remote

import io.github.b150005.skeinly.data.sync.RemoteChartVariationSyncOperations
import io.github.b150005.skeinly.domain.model.ChartVariation
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

class RemoteChartVariationDataSource(
    private val supabaseClient: SupabaseClient,
) : RemoteChartVariationSyncOperations {
    private val table get() = supabaseClient.postgrest["chart_variations"]

    suspend fun getByPatternId(patternId: String): List<ChartVariation> =
        table
            .select {
                filter { eq("pattern_id", patternId) }
            }.decodeList()

    /**
     * Idempotent on `(pattern_id, branch_name)` — `onConflict` targets the
     * UNIQUE constraint so two devices racing `ensureDefaultBranch` on the
     * same fork resolve cleanly without surfacing a 409 to the caller.
     */
    override suspend fun upsert(branch: ChartVariation): ChartVariation =
        table
            .upsert(branch) {
                onConflict = "pattern_id,branch_name"
                select()
            }.decodeSingle()

    override suspend fun delete(id: String) {
        table.delete {
            filter { eq("id", id) }
        }
    }
}
