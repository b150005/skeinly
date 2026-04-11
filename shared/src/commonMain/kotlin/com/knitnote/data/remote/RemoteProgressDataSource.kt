package com.knitnote.data.remote

import com.knitnote.data.sync.RemoteProgressSyncOperations
import com.knitnote.domain.model.Progress
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

class RemoteProgressDataSource(
    private val supabaseClient: SupabaseClient,
) : RemoteProgressSyncOperations {
    private val table get() = supabaseClient.postgrest["progress"]

    suspend fun getByProjectId(projectId: String): List<Progress> =
        table.select {
            filter { eq("project_id", projectId) }
            order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
        }.decodeList()

    override suspend fun upsert(progress: Progress): Progress =
        table.upsert(progress) {
            select()
        }.decodeSingle()

    override suspend fun delete(id: String) {
        table.delete {
            filter { eq("id", id) }
        }
    }
}
