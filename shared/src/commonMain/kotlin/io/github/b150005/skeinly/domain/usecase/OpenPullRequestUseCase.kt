package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.PullRequest
import io.github.b150005.skeinly.domain.model.PullRequestStatus
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.ChartRevisionRepository
import io.github.b150005.skeinly.domain.repository.PullRequestRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Phase 38.3 (ADR-014 §1, §3, §6) — open a pull request from a forked
 * pattern's branch tip against its upstream.
 *
 * **Common-ancestor walk.** ADR-014 §1 + §3 require [PullRequest.commonAncestorRevisionId]
 * be captured at PR-open time as the most recent revision present in BOTH
 * histories. The walk:
 *
 * 1. Snapshot the target's full history into a `Set<revisionId>` (cheap;
 *    target history is in local cache by the time the user navigates from
 *    a forked chart's overflow).
 * 2. Starting from `sourceTipRevisionId`, walk the source's
 *    `parent_revision_id` chain.
 * 3. The first revision whose id appears in the target set is the common
 *    ancestor. The fork point itself was a commit on the upstream so it
 *    will always exist on both sides; if it doesn't (e.g. upstream history
 *    pruned out from under the fork) surface a Validation error rather
 *    than write a malformed PR.
 *
 * Walk depth is bounded by [MAX_WALK_DEPTH] as defense-in-depth against a
 * pathological lineage that has somehow become circular client-side. In
 * practice the walk terminates at the fork point on the first iteration
 * for a fork that has never been edited.
 *
 * **Title / description validation** mirrors ADR-014 §1's soft 200 / 2000
 * char limits, enforced client-side.
 */
@OptIn(ExperimentalUuidApi::class)
class OpenPullRequestUseCase(
    private val pullRequestRepository: PullRequestRepository,
    private val chartRevisionRepository: ChartRevisionRepository,
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(
        sourcePatternId: String,
        sourceBranchId: String,
        sourceTipRevisionId: String,
        targetPatternId: String,
        targetBranchId: String,
        title: String,
        description: String?,
    ): UseCaseResult<PullRequest> {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) {
            return UseCaseResult.Failure(UseCaseError.FieldRequired)
        }
        if (trimmedTitle.length > MAX_TITLE_LENGTH) {
            return UseCaseResult.Failure(
                UseCaseError.FieldTooLong,
            )
        }
        val cleanedDescription = description?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanedDescription != null && cleanedDescription.length > MAX_DESCRIPTION_LENGTH) {
            return UseCaseResult.Failure(UseCaseError.FieldTooLong)
        }
        val authorId =
            authRepository.getCurrentUserId()
                ?: return UseCaseResult.Failure(UseCaseError.SignInRequired)

        return try {
            val targetHistoryIds =
                chartRevisionRepository
                    .getHistoryForPattern(
                        patternId = targetPatternId,
                        limit = MAX_TARGET_HISTORY_LIMIT,
                        offset = 0,
                    ).map { it.revisionId }
                    .toSet()
            if (targetHistoryIds.isEmpty()) {
                return UseCaseResult.Failure(
                    UseCaseError.OperationNotAllowed,
                )
            }

            val ancestorId =
                walkParentChainForCommonAncestor(sourceTipRevisionId, targetHistoryIds)
                    ?: return UseCaseResult.Failure(
                        UseCaseError.OperationNotAllowed,
                    )

            val now = Clock.System.now()
            val pr =
                PullRequest(
                    id = Uuid.random().toString(),
                    sourcePatternId = sourcePatternId,
                    sourceBranchId = sourceBranchId,
                    sourceTipRevisionId = sourceTipRevisionId,
                    targetPatternId = targetPatternId,
                    targetBranchId = targetBranchId,
                    commonAncestorRevisionId = ancestorId,
                    authorId = authorId,
                    title = trimmedTitle,
                    description = cleanedDescription,
                    status = PullRequestStatus.OPEN,
                    mergedRevisionId = null,
                    mergedAt = null,
                    closedAt = null,
                    createdAt = now,
                    updatedAt = now,
                )
            UseCaseResult.Success(pullRequestRepository.openPullRequest(pr))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }

    /**
     * Walks the source's parent chain starting from [startRevisionId] until it
     * hits a revision whose id is in [targetHistoryIds]. The start revision
     * itself counts — if the source tip already exists in target's history
     * (no source-side commits since fork), the source tip IS the common
     * ancestor.
     */
    private suspend fun walkParentChainForCommonAncestor(
        startRevisionId: String,
        targetHistoryIds: Set<String>,
    ): String? {
        var currentId: String? = startRevisionId
        var depth = 0
        while (currentId != null && depth < MAX_WALK_DEPTH) {
            if (currentId in targetHistoryIds) return currentId
            val current = chartRevisionRepository.getRevision(currentId) ?: return null
            currentId = current.parentRevisionId
            depth += 1
        }
        return null
    }

    companion object {
        const val MAX_TITLE_LENGTH: Int = 200
        const val MAX_DESCRIPTION_LENGTH: Int = 2000

        /**
         * Largest target-history page the use case fetches. The set is built
         * once and reused for the parent-chain walk; pagination beyond this
         * limit would require multiple round trips per walk step. 1000 covers
         * any realistic per-pattern revision count without surfacing a
         * pagination boundary in the UX.
         */
        const val MAX_TARGET_HISTORY_LIMIT: Int = 1000

        /**
         * Defense-in-depth bound on the parent-chain walk. A correctly-formed
         * source chain with depth N terminates at the fork point in N steps
         * (worst case = N source-side edits since fork). 1000 is a safety net
         * against pathological circular chains rather than a UX limit.
         */
        const val MAX_WALK_DEPTH: Int = 1000
    }
}
