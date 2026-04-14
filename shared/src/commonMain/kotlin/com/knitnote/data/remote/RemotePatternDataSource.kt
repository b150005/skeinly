package com.knitnote.data.remote

import com.knitnote.data.sync.RemotePatternSyncOperations
import com.knitnote.domain.model.Pattern
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

class RemotePatternDataSource(
    private val supabaseClient: SupabaseClient,
) : RemotePatternSyncOperations,
    PublicPatternDataSource {
    private val table get() = supabaseClient.postgrest["patterns"]

    suspend fun getByOwnerId(ownerId: String): List<Pattern> =
        table
            .select {
                filter { eq("owner_id", ownerId) }
            }.decodeList()

    suspend fun getById(id: String): Pattern? =
        table
            .select {
                filter { eq("id", id) }
            }.decodeSingleOrNull()

    override suspend fun getPublic(
        searchQuery: String,
        limit: Int,
    ): List<Pattern> =
        table
            .select {
                filter {
                    eq("visibility", "public")
                    if (searchQuery.isNotBlank()) {
                        ilike("title", "%$searchQuery%")
                    }
                }
                order("created_at", io.github.jan.supabase.postgrest.query.Order.DESCENDING)
                limit(limit.toLong())
            }.decodeList()

    override suspend fun upsert(pattern: Pattern): Pattern =
        table
            .upsert(pattern) {
                select()
            }.decodeSingle()

    override suspend fun update(pattern: Pattern): Pattern =
        table
            .update(pattern) {
                select()
                filter { eq("id", pattern.id) }
            }.decodeSingle()

    override suspend fun delete(id: String) {
        table.delete {
            filter { eq("id", id) }
        }
    }
}
