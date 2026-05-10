package io.github.b150005.skeinly.data.remote

import io.github.b150005.skeinly.data.sync.RemoteChartBranchSyncOperations
import io.github.b150005.skeinly.domain.model.ChartBranch
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

class RemoteChartBranchDataSource(
    private val supabaseClient: SupabaseClient,
) : RemoteChartBranchSyncOperations {
    private val table get() = supabaseClient.postgrest["chart_branches"]

    suspend fun getByPatternId(patternId: String): List<ChartBranch> =
        table
            .select {
                filter { eq("pattern_id", patternId) }
            }.decodeList()

    /**
     * Idempotent on `(pattern_id, branch_name)` — `onConflict` targets the
     * UNIQUE constraint so two devices racing `ensureDefaultBranch` on the
     * same fork resolve cleanly without surfacing a 409 to the caller.
     */
    override suspend fun upsert(branch: ChartBranch): ChartBranch =
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
