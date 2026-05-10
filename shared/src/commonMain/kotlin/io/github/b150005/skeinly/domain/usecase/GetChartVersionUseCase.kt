package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.ChartVersion
import io.github.b150005.skeinly.domain.repository.ChartVersionRepository

/**
 * Resolves a single revision by its commit identifier (ADR-013 §4).
 *
 * Phase 37.2 ships this use case alongside [GetChartHistoryUseCase] so the
 * Phase 37.3 `ChartComparisonScreen` load path can `get(revisionId)` against either
 * side of a diff without touching DI. A null return means the revision is not
 * in the local cache and the remote could not resolve it (caller renders an
 * empty/error state).
 */
class GetChartVersionUseCase(
    private val repository: ChartVersionRepository,
) {
    suspend operator fun invoke(revisionId: String): UseCaseResult<ChartVersion?> =
        try {
            UseCaseResult.Success(repository.getRevision(revisionId))
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
