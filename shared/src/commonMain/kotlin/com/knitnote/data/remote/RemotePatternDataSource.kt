package com.knitnote.data.remote

import com.knitnote.data.sync.RemotePatternSyncOperations
import com.knitnote.domain.model.Pattern
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

class RemotePatternDataSource(
    private val supabaseClient: SupabaseClient,
) : RemotePatternSyncOperations {
    private val table get() = supabaseClient.postgrest["patterns"]

    suspend fun getByOwnerId(ownerId: String): List<Pattern> =
        table.select {
            filter { eq("owner_id", ownerId) }
        }.decodeList()

    suspend fun getById(id: String): Pattern? =
        table.select {
            filter { eq("id", id) }
        }.decodeSingleOrNull()

    override suspend fun insert(pattern: Pattern): Pattern {
        table.insert(pattern)
        return pattern
    }

    override suspend fun update(pattern: Pattern): Pattern {
        table.update(pattern) {
            filter { eq("id", pattern.id) }
        }
        return pattern
    }

    override suspend fun delete(id: String) {
        table.delete {
            filter { eq("id", id) }
        }
    }
}
