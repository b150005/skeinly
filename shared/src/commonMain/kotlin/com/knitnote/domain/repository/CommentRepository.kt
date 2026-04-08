package com.knitnote.domain.repository

import com.knitnote.domain.model.Comment
import com.knitnote.domain.model.CommentTargetType
import kotlinx.coroutines.flow.Flow

interface CommentRepository {
    suspend fun getById(id: String): Comment?
    suspend fun getByTarget(targetType: CommentTargetType, targetId: String): List<Comment>
    fun observeByTarget(targetType: CommentTargetType, targetId: String): Flow<List<Comment>>
    suspend fun create(comment: Comment): Comment
    suspend fun delete(id: String)
}
