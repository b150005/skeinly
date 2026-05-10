package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.ChartVersion
import io.github.b150005.skeinly.domain.repository.ChartVersionRepository
import kotlinx.coroutines.flow.Flow

/**
 * Read-side of the Phase 37 chart commit history (ADR-013 §4 / §6).
 *
 * Wraps [ChartVersionRepository.observeHistoryForPattern] for the live
 * `ChartHistoryViewModel` flow, plus a one-shot suspend invoke for paginated
 * page-fetch call sites that may land in 37.4. Newest-first ordering is
 * established by the repository's `created_at DESC` query — this use case
 * preserves it without re-sorting.
 */
class GetChartHistoryUseCase(
    private val repository: ChartVersionRepository,
) {
    fun observe(patternId: String): Flow<List<ChartVersion>> = repository.observeHistoryForPattern(patternId)

    suspend operator fun invoke(
        patternId: String,
        limit: Int = ChartVersionRepository.DEFAULT_LIMIT,
        offset: Int = 0,
    ): UseCaseResult<List<ChartVersion>> =
        try {
            UseCaseResult.Success(repository.getHistoryForPattern(patternId, limit, offset))
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
