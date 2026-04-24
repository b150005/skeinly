package io.github.b150005.knitnote.data.remote

import io.github.b150005.knitnote.data.sync.RemoteProjectSegmentSyncOperations
import io.github.b150005.knitnote.domain.model.ProjectSegment
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

class RemoteProjectSegmentDataSource(
    private val supabaseClient: SupabaseClient,
) : RemoteProjectSegmentSyncOperations {
    private val table get() = supabaseClient.postgrest["project_segments"]

    suspend fun getByProjectId(projectId: String): List<ProjectSegment> =
        table
            .select {
                filter { eq("project_id", projectId) }
                order("updated_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
            }.decodeList()

    override suspend fun upsert(segment: ProjectSegment): ProjectSegment =
        table
            .upsert(segment) {
                select()
            }.decodeSingle()

    override suspend fun delete(id: String) {
        table.delete {
            filter { eq("id", id) }
        }
    }

    suspend fun deleteByProjectId(projectId: String) {
        table.delete {
            filter { eq("project_id", projectId) }
        }
    }
}
