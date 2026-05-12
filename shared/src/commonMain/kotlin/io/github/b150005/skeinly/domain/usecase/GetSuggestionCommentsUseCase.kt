package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.SuggestionComment
import io.github.b150005.skeinly.domain.repository.SuggestionRepository
import kotlinx.coroutines.flow.Flow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Phase 38.3 (ADR-014 §6, §8) — comment thread for a single suggestion.
 *
 * Local-only observe; the cold-launch seed is owned by [SuggestionRepository.subscribeToCommentsChannel]
 * which kicks a remote fetch under the same lock as the channel attach. The
 * [observe] Flow stays warm thereafter via the per-suggestion Realtime channel
 * (`suggestion-comments-<suggestionId>` per ADR-014 §7).
 *
 * Comments are append-only at RLS, so observers never see UPDATE / DELETE.
 */
class GetSuggestionCommentsUseCase(
    private val repository: SuggestionRepository,
) {
    fun observe(suggestionId: String): Flow<List<SuggestionComment>> = repository.observeCommentsForSuggestion(suggestionId)

    suspend operator fun invoke(suggestionId: String): UseCaseResult<List<SuggestionComment>> =
        try {
            UseCaseResult.Success(repository.getCommentsForSuggestion(suggestionId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
