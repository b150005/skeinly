package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.chart.ChartDiffAlgorithm
import io.github.b150005.knitnote.domain.model.ChartDiff
import io.github.b150005.knitnote.domain.model.toStructuredChart
import io.github.b150005.knitnote.domain.repository.ChartRevisionRepository
import kotlin.coroutines.cancellation.CancellationException

/**
 * Resolve two chart revisions and compute their diff per ADR-013 §5 / §6 (Phase 37.3).
 *
 * - [baseRevisionId] is nullable so the initial-commit case ("the very first
 *   revision in a chart's history has no parent") loads cleanly. When null,
 *   the algorithm diffs against an empty chart and every layer surfaces as
 *   added.
 * - [targetRevisionId] is required. A missing target is a hard failure — the
 *   diff screen has nothing to render.
 * - When [baseRevisionId] is non-null but the lookup misses, surface as
 *   [UseCaseError.NotFound] so the screen renders an explicit error rather
 *   than silently degrading to "initial commit" treatment (the two are
 *   semantically distinct — degrading would hide a sync drift).
 */
class GetChartDiffUseCase(
    private val repository: ChartRevisionRepository,
) {
    suspend operator fun invoke(
        baseRevisionId: String?,
        targetRevisionId: String,
    ): UseCaseResult<ChartDiff> =
        try {
            val target =
                repository.getRevision(targetRevisionId)
                    ?: return UseCaseResult.Failure(
                        UseCaseError.NotFound("Revision $targetRevisionId not found"),
                    )
            val base =
                if (baseRevisionId == null) {
                    null
                } else {
                    repository.getRevision(baseRevisionId)
                        ?: return UseCaseResult.Failure(
                            UseCaseError.NotFound("Revision $baseRevisionId not found"),
                        )
                }
            UseCaseResult.Success(
                ChartDiffAlgorithm.diff(
                    base = base?.toStructuredChart(),
                    target = target.toStructuredChart(),
                ),
            )
        } catch (e: CancellationException) {
            // Codebase-wide invariant — never swallow CancellationException.
            // Same precedent as ForkPublicPatternUseCase. Without this, navigating
            // away mid-load surfaces a stale Snackbar on the next destination.
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
