package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.repository.PullRequestRepository
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Read-side of the Phase 38.2 outgoing pull-request list (ADR-014 §6, §8).
 *
 * Symmetric to [GetIncomingPullRequestsUseCase] — wraps
 * [PullRequestRepository.observeOutgoingForOwner] for the live ViewModel flow,
 * plus a one-shot suspend invoke for the cold-launch backfill.
 */
class GetOutgoingPullRequestsUseCase(
    private val repository: PullRequestRepository,
) {
    fun observe(ownerId: String): Flow<List<PullRequest>> = repository.observeOutgoingForOwner(ownerId)

    suspend operator fun invoke(ownerId: String): UseCaseResult<List<PullRequest>> =
        try {
            UseCaseResult.Success(repository.getOutgoingForOwner(ownerId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
