package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.PullRequestComment
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.PullRequestRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Phase 38.3 (ADR-014 §6, §8) — post a comment on an open pull request.
 *
 * Validates body length client-side (≤ 5000 chars per migration 016 CHECK
 * and ADR-014 §1) and rejects blank-after-trim bodies. Generates the row id
 * + createdAt locally; the remote upsert path tolerates either side
 * generating these (idempotent on `id`).
 *
 * Caller (PullRequestDetailViewModel) is responsible for ensuring the PR is
 * still OPEN — the RLS policy permits comment INSERTs by either party while
 * a PR is OPEN, MERGED, or CLOSED, but the UX gates the input on OPEN.
 */
@OptIn(ExperimentalUuidApi::class)
class PostPullRequestCommentUseCase(
    private val repository: PullRequestRepository,
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(
        pullRequestId: String,
        body: String,
    ): UseCaseResult<PullRequestComment> {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) {
            return UseCaseResult.Failure(UseCaseError.FieldRequired)
        }
        if (trimmed.length > MAX_BODY_LENGTH) {
            return UseCaseResult.Failure(
                UseCaseError.FieldTooLong,
            )
        }
        val authorId =
            authRepository.getCurrentUserId()
                ?: return UseCaseResult.Failure(
                    UseCaseError.Authentication(IllegalStateException("Must be signed in to post a comment")),
                )

        return try {
            val comment =
                PullRequestComment(
                    id = Uuid.random().toString(),
                    pullRequestId = pullRequestId,
                    authorId = authorId,
                    body = trimmed,
                    createdAt = Clock.System.now(),
                )
            UseCaseResult.Success(repository.postComment(comment))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }

    companion object {
        const val MAX_BODY_LENGTH: Int = 5000
    }
}
