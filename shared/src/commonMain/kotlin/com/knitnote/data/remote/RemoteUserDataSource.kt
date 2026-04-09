package com.knitnote.data.remote

import com.knitnote.domain.model.User
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

class RemoteUserDataSource(
    private val supabaseClient: SupabaseClient,
) {
    private val table get() = supabaseClient.postgrest["profiles"]

    suspend fun getById(id: String): User? =
        table.select {
            filter { eq("id", id) }
        }.decodeSingleOrNull()

    suspend fun searchByDisplayName(query: String, limit: Int = 10): List<User> =
        table.select {
            filter { ilike("display_name", "%$query%") }
            limit(limit.toLong())
        }.decodeList()
}
