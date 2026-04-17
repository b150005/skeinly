package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.Comment
import io.github.b150005.knitnote.domain.model.CommentTargetType
import io.github.b150005.knitnote.domain.repository.CommentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeCommentRepository : CommentRepository {
    private val comments = MutableStateFlow<List<Comment>>(emptyList())
    var closeChannelCalled = false

    override suspend fun closeChannel() {
        closeChannelCalled = true
    }

    override suspend fun getById(id: String): Comment? = comments.value.find { it.id == id }

    override suspend fun getByTarget(
        targetType: CommentTargetType,
        targetId: String,
    ): List<Comment> = comments.value.filter { it.targetType == targetType && it.targetId == targetId }

    override fun observeByTarget(
        targetType: CommentTargetType,
        targetId: String,
    ): Flow<List<Comment>> =
        comments.map { list ->
            list.filter { it.targetType == targetType && it.targetId == targetId }
        }

    override suspend fun create(comment: Comment): Comment {
        comments.value = comments.value + comment
        return comment
    }

    override suspend fun delete(id: String) {
        comments.value = comments.value.filter { it.id != id }
    }

    fun addComment(comment: Comment) {
        comments.value = comments.value + comment
    }
}
