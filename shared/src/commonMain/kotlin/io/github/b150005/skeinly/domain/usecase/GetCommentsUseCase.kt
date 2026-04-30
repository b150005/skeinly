package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.Comment
import io.github.b150005.skeinly.domain.model.CommentTargetType
import io.github.b150005.skeinly.domain.repository.CommentRepository
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
                UseCaseError.RequiresConnectivity,
            )
        }
        return UseCaseResult.Success(
            commentRepository.getByTarget(targetType, targetId),
        )
    }
}
