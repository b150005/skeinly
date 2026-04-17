package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.CommentRepository

class DeleteCommentUseCase(
    private val commentRepository: CommentRepository?,
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(commentId: String): UseCaseResult<Unit> {
        if (commentRepository == null) {
            return UseCaseResult.Failure(
                UseCaseError.Validation("Comments require cloud connectivity"),
            )
        }

        val userId =
            authRepository.getCurrentUserId()
                ?: return UseCaseResult.Failure(
                    UseCaseError.Validation("Must be signed in to delete comments"),
                )

        val comment = commentRepository.getById(commentId)

        // Use same error message for "not found" and "not owner" to prevent ID enumeration
        if (comment == null || comment.authorId != userId) {
            return UseCaseResult.Failure(
                UseCaseError.NotFound("Comment not found"),
            )
        }

        commentRepository.delete(commentId)
        return UseCaseResult.Success(Unit)
    }
}
