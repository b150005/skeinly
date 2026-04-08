package com.knitnote.data.remote

import com.knitnote.domain.model.Project
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

class RemoteProjectDataSource(
    private val supabaseClient: SupabaseClient,
) {
    private val table get() = supabaseClient.postgrest["projects"]

    suspend fun getByOwnerId(ownerId: String): List<Project> =
        table.select {
            filter { eq("owner_id", ownerId) }
        }.decodeList()

    suspend fun getById(id: String): Project? =
        table.select {
            filter { eq("id", id) }
        }.decodeSingleOrNull()

    suspend fun insert(project: Project): Project {
        table.insert(project)
        return project
    }

    suspend fun update(project: Project): Project {
        table.update(project) {
            filter { eq("id", project.id) }
        }
        return project
    }

    suspend fun delete(id: String) {
        table.delete {
            filter { eq("id", id) }
        }
    }
}
