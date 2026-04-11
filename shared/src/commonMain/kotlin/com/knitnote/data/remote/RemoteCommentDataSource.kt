package com.knitnote.data.remote

import com.knitnote.domain.model.Comment
import com.knitnote.domain.model.CommentTargetType
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest

class RemoteCommentDataSource(
    private val supabaseClient: SupabaseClient,
) {
    private val table get() = supabaseClient.postgrest["comments"]

    suspend fun getById(id: String): Comment? =
        table.select {
            filter { eq("id", id) }
        }.decodeSingleOrNull()

    suspend fun getByTarget(
        targetType: CommentTargetType,
        targetId: String,
    ): List<Comment> =
        table.select {
            filter {
                eq("target_type", targetType.name.lowercase())
                eq("target_id", targetId)
            }
            order("created_at", io.github.jan.supabase.postgrest.query.Order.ASCENDING)
        }.decodeList()

    suspend fun insert(comment: Comment): Comment =
        table.insert(comment) {
            select()
        }.decodeSingle()

    suspend fun delete(id: String) {
        table.delete {
            filter { eq("id", id) }
        }
    }
}
