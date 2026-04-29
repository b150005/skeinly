package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.repository.ChartBranchRepository
import io.github.b150005.knitnote.domain.repository.ChartRevisionRepository
import io.github.b150005.knitnote.domain.repository.StructuredChartRepository
import kotlinx.coroutines.CancellationException

/**
 * Check out a branch by materializing its tip revision into the
 * `chart_documents` row (Phase 37.4, ADR-013 §7).
 *
 * Switching branches is pointer movement, NOT a new commit. The current
 * `chart_documents` row is rewritten in place (preserving its primary key
 * `id` so any external FK is unaffected) with the target branch tip
 * revision's payload. No history append. The branch's `tip_revision_id`
 * already points at the desired revision so no branch-tip mutation either.
 *
 * Concurrency: the use case reads the branch + revision rows outside any
 * mutex (these are immutable once written, so no read-write race), then
 * delegates the read+write of the `chart_documents` tip pointer to
 * `StructuredChartRepository.setTip` which serializes through the same
 * `writeMutex` that `update`/`create`/`forkFor` use. A concurrent
 * chart-editor `update()` racing the switch can no longer silently roll
 * back the editor's save — see `setTip` KDoc.
 *
 * Failures:
 * - Unknown `(patternId, branchName)` → `NotFound`.
 * - Branch's `tip_revision_id` doesn't resolve (rare; would indicate a sync
 *   tear between `chart_branches` and `chart_revisions`) → `NotFound`.
 * - No tip pointer row exists for the pattern at all → `NotFound`. This
 *   shouldn't happen post-37.1 since `ensureDefaultBranch` only fires when
 *   the chart was created, but the guard surfaces broken invariants
 *   immediately.
 */
class SwitchBranchUseCase(
    private val branchRepository: ChartBranchRepository,
    private val revisionRepository: ChartRevisionRepository,
    private val chartRepository: StructuredChartRepository,
) {
    suspend operator fun invoke(
        patternId: String,
        branchName: String,
    ): UseCaseResult<StructuredChart> =
        try {
            val branch = branchRepository.getByPatternIdAndName(patternId, branchName)
            if (branch == null) {
                UseCaseResult.Failure(UseCaseError.ResourceNotFound)
            } else {
                val revision = revisionRepository.getRevision(branch.tipRevisionId)
                if (revision == null) {
                    UseCaseResult.Failure(UseCaseError.ResourceNotFound)
                } else {
                    val rebuilt = chartRepository.setTip(patternId, revision)
                    if (rebuilt == null) {
                        UseCaseResult.Failure(UseCaseError.ResourceNotFound)
                    } else {
                        UseCaseResult.Success(rebuilt)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
