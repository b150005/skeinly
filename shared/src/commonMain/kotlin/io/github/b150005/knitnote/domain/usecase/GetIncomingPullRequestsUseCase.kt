package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.repository.PullRequestRepository
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Read-side of the Phase 38.2 incoming pull-request list (ADR-014 §6, §8).
 *
 * Wraps [PullRequestRepository.observeIncomingForOwner] for the live
 * `PullRequestListViewModel` flow, plus a one-shot suspend invoke for the
 * cold-launch backfill described in [PullRequestRepository.observeIncomingForOwner]'s
 * KDoc — local-only observe emits whatever is currently cached, so the
 * ViewModel must seed the cache via [invoke] before subscribing.
 *
 * The repository is unconditionally registered in `RepositoryModule` (it falls
 * back to local-only behavior with `remote = null` when Supabase isn't
 * configured), so this use case takes a non-null param matching the
 * [GetChartHistoryUseCase] precedent.
 */
class GetIncomingPullRequestsUseCase(
    private val repository: PullRequestRepository,
) {
    fun observe(ownerId: String): Flow<List<PullRequest>> = repository.observeIncomingForOwner(ownerId)

    suspend operator fun invoke(ownerId: String): UseCaseResult<List<PullRequest>> =
        try {
            UseCaseResult.Success(repository.getIncomingForOwner(ownerId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
