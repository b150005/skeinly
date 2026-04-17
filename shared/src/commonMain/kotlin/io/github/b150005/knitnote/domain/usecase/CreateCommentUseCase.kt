package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.ActivityTargetType
import io.github.b150005.knitnote.domain.model.ActivityType
import io.github.b150005.knitnote.domain.model.Comment
import io.github.b150005.knitnote.domain.model.CommentTargetType
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.CommentRepository
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private const val MAX_COMMENT_LENGTH = 2000
private val UUID_REGEX = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

class CreateCommentUseCase(
    private val commentRepository: CommentRepository?,
    private val authRepository: AuthRepository,
    private val createActivity: CreateActivityUseCase? = null,
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend operator fun invoke(
        targetType: CommentTargetType,
        targetId: String,
        body: String,
    ): UseCaseResult<Comment> {
        if (commentRepository == null) {
            return UseCaseResult.Failure(
                UseCaseError.Validation("Comments require cloud connectivity"),
            )
        }

        val userId =
            authRepository.getCurrentUserId()
                ?: return UseCaseResult.Failure(
                    UseCaseError.Validation("Must be signed in to comment"),
                )

        if (!UUID_REGEX.matches(targetId)) {
            return UseCaseResult.Failure(
                UseCaseError.Validation("Invalid target ID"),
            )
        }

        val trimmedBody = body.trim()
        if (trimmedBody.isEmpty()) {
            return UseCaseResult.Failure(
                UseCaseError.Validation("Comment body cannot be empty"),
            )
        }
        if (trimmedBody.length > MAX_COMMENT_LENGTH) {
            return UseCaseResult.Failure(
                UseCaseError.Validation("Comment cannot exceed $MAX_COMMENT_LENGTH characters"),
            )
        }

        val comment =
            Comment(
                id = Uuid.random().toString(),
                authorId = userId,
                targetType = targetType,
                targetId = targetId,
                body = trimmedBody,
                createdAt = Clock.System.now(),
            )

        val created = commentRepository.create(comment)

        val activityTargetType =
            when (targetType) {
                CommentTargetType.PROJECT -> ActivityTargetType.PROJECT
                CommentTargetType.PATTERN -> ActivityTargetType.PATTERN
            }
        createActivity?.invoke(
            userId = userId,
            type = ActivityType.COMMENTED,
            targetType = activityTargetType,
            targetId = targetId,
        )

        return UseCaseResult.Success(created)
    }
}
