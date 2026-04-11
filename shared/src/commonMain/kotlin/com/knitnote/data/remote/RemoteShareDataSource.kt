package com.knitnote.data.remote

import com.knitnote.domain.model.Share
import com.knitnote.domain.model.ShareStatus
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

class RemoteShareDataSource(
    private val supabaseClient: SupabaseClient,
) {
    private val table get() = supabaseClient.postgrest["shares"]

    suspend fun getById(id: String): Share? =
        table.select {
            filter { eq("id", id) }
        }.decodeSingleOrNull()

    suspend fun getByPatternId(patternId: String): List<Share> =
        table.select {
            filter { eq("pattern_id", patternId) }
        }.decodeList()

    suspend fun getByToken(token: String): Share? =
        table.select {
            filter { eq("share_token", token) }
        }.decodeSingleOrNull()

    suspend fun getReceivedByUserId(userId: String): List<Share> =
        table.select {
            filter { eq("to_user_id", userId) }
        }.decodeList()

    suspend fun getSentByUserId(userId: String): List<Share> =
        table.select {
            filter { eq("from_user_id", userId) }
        }.decodeList()

    suspend fun insert(share: Share): Share =
        table.insert(share) {
            select()
        }.decodeSingle()

    suspend fun updateStatus(
        id: String,
        status: ShareStatus,
    ) {
        table.update({ set("status", status.name.lowercase()) }) {
            filter { eq("id", id) }
        }
    }

    suspend fun delete(id: String) {
        table.delete {
            filter { eq("id", id) }
        }
    }
}
