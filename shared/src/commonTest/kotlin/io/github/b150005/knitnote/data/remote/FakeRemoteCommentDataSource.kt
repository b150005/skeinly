package io.github.b150005.knitnote.data.remote

import io.github.b150005.knitnote.domain.model.Comment
import io.github.b150005.knitnote.domain.model.CommentTargetType

/**
 * In-memory fake for [CommentDataSourceOperations] used in repository tests.
 */
class FakeRemoteCommentDataSource : CommentDataSourceOperations {
    private val comments = mutableListOf<Comment>()

    override suspend fun getById(id: String): Comment? = comments.find { it.id == id }

    override suspend fun getByTarget(
        targetType: CommentTargetType,
        targetId: String,
    ): List<Comment> = comments.filter { it.targetType == targetType && it.targetId == targetId }

    override suspend fun insert(comment: Comment): Comment {
        comments.add(comment)
        return comment
    }

    override suspend fun delete(id: String) {
        comments.removeAll { it.id == id }
    }

    /** Test helper: pre-populate comments. */
    fun addComment(comment: Comment) {
        comments.add(comment)
    }
}
