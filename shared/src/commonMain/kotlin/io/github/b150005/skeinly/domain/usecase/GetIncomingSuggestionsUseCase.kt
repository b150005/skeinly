package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.Suggestion
import io.github.b150005.skeinly.domain.repository.SuggestionRepository
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Read-side of the Phase 38.2 incoming pull-request list (ADR-014 §6, §8).
 *
 * Wraps [SuggestionRepository.observeIncomingForOwner] for the live
 * `SuggestionListViewModel` flow, plus a one-shot suspend invoke for the
 * cold-launch backfill described in [SuggestionRepository.observeIncomingForOwner]'s
 * KDoc — local-only observe emits whatever is currently cached, so the
 * ViewModel must seed the cache via [invoke] before subscribing.
 *
 * The repository is unconditionally registered in `RepositoryModule` (it falls
 * back to local-only behavior with `remote = null` when Supabase isn't
 * configured), so this use case takes a non-null param matching the
 * [GetChartHistoryUseCase] precedent.
 */
class GetIncomingSuggestionsUseCase(
    private val repository: SuggestionRepository,
) {
    fun observe(ownerId: String): Flow<List<Suggestion>> = repository.observeIncomingForOwner(ownerId)

    suspend operator fun invoke(ownerId: String): UseCaseResult<List<Suggestion>> =
        try {
            UseCaseResult.Success(repository.getIncomingForOwner(ownerId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
