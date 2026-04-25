package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.ChartBranch
import io.github.b150005.knitnote.domain.repository.ChartBranchRepository
import io.github.b150005.knitnote.domain.repository.StructuredChartRepository
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Create a new branch pointing at the chart's current tip revision (Phase 37.4,
 * ADR-013 §7).
 *
 * Validation:
 * - [branchName] is trimmed; empty after trim → [UseCaseError.Validation].
 * - "main" is the auto-created default; users cannot create a second "main".
 * - Duplicate `(pattern_id, branch_name)` resolves to the existing row at the
 *   repository layer (idempotent). The use case still surfaces a Validation
 *   error before reaching the repo so the picker can show "Branch already
 *   exists" feedback without depending on repo-internal idempotency.
 *
 * The new branch's `tipRevisionId` is the current chart's `revisionId`. There
 * is no "branch from arbitrary revision" affordance in v1 — branching from a
 * past revision is the same shape as `RestoreRevisionUseCase` followed by a
 * branch from the current tip; deferred to keep the 37.4 surface bounded.
 */
@OptIn(ExperimentalUuidApi::class)
class CreateBranchUseCase(
    private val branchRepository: ChartBranchRepository,
    private val chartRepository: StructuredChartRepository,
) {
    suspend operator fun invoke(
        patternId: String,
        branchName: String,
        ownerId: String,
    ): UseCaseResult<ChartBranch> {
        val trimmed = branchName.trim()
        if (trimmed.isEmpty()) {
            return UseCaseResult.Failure(UseCaseError.Validation("Branch name is required"))
        }
        if (trimmed.equals(ChartBranch.DEFAULT_BRANCH_NAME, ignoreCase = true)) {
            return UseCaseResult.Failure(
                UseCaseError.Validation("'${ChartBranch.DEFAULT_BRANCH_NAME}' is reserved"),
            )
        }
        return try {
            // Duplicate check is case-insensitive to match the "main"
            // reservation above — a user who types "Feature" should see the
            // same "already exists" feedback whether the existing branch is
            // "Feature" or "feature". The DB itself stores the user's
            // chosen casing (no lowercase normalization at write time), so
            // already-stored branches with mixed case continue rendering
            // exactly as the user named them.
            val existingBranches = branchRepository.getByPatternId(patternId)
            if (existingBranches.any { it.branchName.equals(trimmed, ignoreCase = true) }) {
                return UseCaseResult.Failure(UseCaseError.Validation("Branch already exists"))
            }
            val chart =
                chartRepository.getByPatternId(patternId)
                    ?: return UseCaseResult.Failure(UseCaseError.NotFound("Chart not found"))
            val now = Clock.System.now()
            val branch =
                ChartBranch(
                    id = Uuid.random().toString(),
                    patternId = patternId,
                    ownerId = ownerId,
                    branchName = trimmed,
                    tipRevisionId = chart.revisionId,
                    createdAt = now,
                    updatedAt = now,
                )
            UseCaseResult.Success(branchRepository.createBranch(branch))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }
}
