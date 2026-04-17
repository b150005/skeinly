package io.github.b150005.knitnote.domain.repository

import io.github.b150005.knitnote.domain.model.Comment
import io.github.b150005.knitnote.domain.model.CommentTargetType
import kotlinx.coroutines.flow.Flow

interface CommentRepository {
    suspend fun getById(id: String): Comment?

    suspend fun getByTarget(
        targetType: CommentTargetType,
        targetId: String,
    ): List<Comment>

    fun observeByTarget(
        targetType: CommentTargetType,
        targetId: String,
    ): Flow<List<Comment>>

    suspend fun create(comment: Comment): Comment

    suspend fun delete(id: String)

    /** Release Realtime subscription and clear cached state. Call on user logout. */
    suspend fun closeChannel() {}
}
