package com.knitnote.data.remote

import com.knitnote.domain.model.Share
import com.knitnote.domain.model.ShareStatus
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

/** Testable contract for remote share operations. */
interface ShareDataSourceOperations {
    suspend fun getById(id: String): Share?

    suspend fun getByPatternId(patternId: String): List<Share>

    suspend fun getByToken(token: String): Share?

    suspend fun getReceivedByUserId(userId: String): List<Share>

    suspend fun insert(share: Share): Share

    suspend fun updateStatus(
        id: String,
        status: ShareStatus,
    )

    suspend fun delete(id: String)
}

class RemoteShareDataSource(
    private val supabaseClient: SupabaseClient,
) : ShareDataSourceOperations {
    private val table get() = supabaseClient.postgrest["shares"]

    override suspend fun getById(id: String): Share? =
        table
            .select {
                filter { eq("id", id) }
            }.decodeSingleOrNull()

    override suspend fun getByPatternId(patternId: String): List<Share> =
        table
            .select {
                filter { eq("pattern_id", patternId) }
            }.decodeList()

    override suspend fun getByToken(token: String): Share? =
        table
            .select {
                filter { eq("share_token", token) }
            }.decodeSingleOrNull()

    override suspend fun getReceivedByUserId(userId: String): List<Share> =
        table
            .select {
                filter { eq("to_user_id", userId) }
            }.decodeList()

    override suspend fun insert(share: Share): Share =
        table
            .insert(share) {
                select()
            }.decodeSingle()

    override suspend fun updateStatus(
        id: String,
        status: ShareStatus,
    ) {
        table.update({ set("status", status.name.lowercase()) }) {
            filter { eq("id", id) }
        }
    }

    override suspend fun delete(id: String) {
        table.delete {
            filter { eq("id", id) }
        }
    }
}
