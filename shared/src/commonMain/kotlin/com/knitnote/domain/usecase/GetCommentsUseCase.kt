package com.knitnote.domain.usecase

import com.knitnote.domain.model.Comment
import com.knitnote.domain.model.CommentTargetType
import com.knitnote.domain.repository.CommentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

class GetCommentsUseCase(
    private val commentRepository: CommentRepository?,
) {

    fun observe(
        targetType: CommentTargetType,
        targetId: String,
    ): Flow<List<Comment>> {
        if (commentRepository == null) return flowOf(emptyList())
        return commentRepository.observeByTarget(targetType, targetId)
    }

    suspend operator fun invoke(
        targetType: CommentTargetType,
        targetId: String,
    ): UseCaseResult<List<Comment>> {
        if (commentRepository == null) {
            return UseCaseResult.Failure(
                UseCaseError.Validation("Comments require cloud connectivity"),
            )
        }
        return UseCaseResult.Success(
            commentRepository.getByTarget(targetType, targetId),
        )
    }
}
