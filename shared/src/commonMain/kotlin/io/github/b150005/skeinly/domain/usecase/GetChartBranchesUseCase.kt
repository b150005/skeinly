package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.ChartBranch
import io.github.b150005.skeinly.domain.repository.ChartBranchRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Live branch list for a given pattern (Phase 37.4, ADR-013 §7).
 *
 * Used by the branch picker on `ChartViewerScreen`. Order is alphabetical by
 * branch name (SQLDelight `getByPatternId` ORDER BY) — picker UI highlights
 * the current branch (whose `tipRevisionId` matches the current chart's
 * `revisionId`) at the call site, not here.
 */
class GetChartBranchesUseCase(
    private val repository: ChartBranchRepository,
) {
    fun observe(patternId: String): Flow<List<ChartBranch>> = repository.observeBranchesForPattern(patternId)

    suspend operator fun invoke(patternId: String): UseCaseResult<List<ChartBranch>> =
        try {
            UseCaseResult.Success(repository.getByPatternId(patternId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
