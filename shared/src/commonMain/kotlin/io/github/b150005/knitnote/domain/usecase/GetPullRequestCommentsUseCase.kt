package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.PullRequestComment
import io.github.b150005.knitnote.domain.repository.PullRequestRepository
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Phase 38.3 (ADR-014 §6, §8) — comment thread for a single pull request.
 *
 * Local-only observe; the cold-launch seed is owned by [PullRequestRepository.subscribeToCommentsChannel]
 * which kicks a remote fetch under the same lock as the channel attach. The
 * [observe] Flow stays warm thereafter via the per-PR Realtime channel
 * (`pull-request-comments-<prId>` per ADR-014 §7).
 *
 * Comments are append-only at RLS, so observers never see UPDATE / DELETE.
 */
class GetPullRequestCommentsUseCase(
    private val repository: PullRequestRepository,
) {
    fun observe(pullRequestId: String): Flow<List<PullRequestComment>> = repository.observeCommentsForPullRequest(pullRequestId)

    suspend operator fun invoke(pullRequestId: String): UseCaseResult<List<PullRequestComment>> =
        try {
            UseCaseResult.Success(repository.getCommentsForPullRequest(pullRequestId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
