package com.knitnote.data.remote

import com.knitnote.data.sync.RemoteProjectSyncOperations
import com.knitnote.domain.model.Project
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

class RemoteProjectDataSource(
    private val supabaseClient: SupabaseClient,
) : RemoteProjectSyncOperations {
    private val table get() = supabaseClient.postgrest["projects"]

    suspend fun getByOwnerId(ownerId: String): List<Project> =
        table.select {
            filter { eq("owner_id", ownerId) }
        }.decodeList()

    suspend fun getById(id: String): Project? =
        table.select {
            filter { eq("id", id) }
        }.decodeSingleOrNull()

    override suspend fun upsert(project: Project): Project =
        table.upsert(project) {
            select()
        }.decodeSingle()

    override suspend fun update(project: Project): Project =
        table.update(project) {
            select()
            filter { eq("id", project.id) }
        }.decodeSingle()

    override suspend fun delete(id: String) {
        table.delete {
            filter { eq("id", id) }
        }
    }
}
