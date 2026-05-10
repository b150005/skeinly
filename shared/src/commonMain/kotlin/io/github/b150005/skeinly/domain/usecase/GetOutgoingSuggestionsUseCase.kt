package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.Suggestion
import io.github.b150005.skeinly.domain.repository.SuggestionRepository
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Read-side of the Phase 38.2 outgoing pull-request list (ADR-014 §6, §8).
 *
 * Symmetric to [GetIncomingSuggestionsUseCase] — wraps
 * [SuggestionRepository.observeOutgoingForOwner] for the live ViewModel flow,
 * plus a one-shot suspend invoke for the cold-launch backfill.
 */
class GetOutgoingSuggestionsUseCase(
    private val repository: SuggestionRepository,
) {
    fun observe(ownerId: String): Flow<List<Suggestion>> = repository.observeOutgoingForOwner(ownerId)

    suspend operator fun invoke(ownerId: String): UseCaseResult<List<Suggestion>> =
        try {
            UseCaseResult.Success(repository.getOutgoingForOwner(ownerId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
