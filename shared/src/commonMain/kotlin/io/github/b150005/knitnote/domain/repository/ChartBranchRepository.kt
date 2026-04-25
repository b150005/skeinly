package io.github.b150005.knitnote.domain.repository

import io.github.b150005.knitnote.domain.model.ChartBranch
import kotlinx.coroutines.flow.Flow

/**
 * Branch model on top of the append-only revision spine (ADR-013 §7).
 *
 * Phase 37.1 wired the persistence + sync layer; Phase 37.4 surfaces the
 * picker UI and the create / switch / delete mutations.
 *
 * Reads:
 * - [getByPatternIdAndName] resolves a single branch by `(pattern_id, branch_name)`.
 * - [getByPatternId] / [observeBranchesForPattern] feed the branch picker.
 *
 * Writes:
 * - [createBranch] mints a new row pointing at [tipRevisionId]. Idempotent on
 *   the `(pattern_id, branch_name)` UNIQUE constraint — re-creating the same
 *   branch name no-ops (returns the existing row).
 * - [advanceTip] moves an existing branch's `tip_revision_id` forward. Used
 *   by `StructuredChartRepository.update` to track the user's current branch
 *   tip across saves (ADR-013 §7).
 * - [deleteBranch] removes a branch row. The "main" branch cannot be deleted —
 *   guarded at the use-case layer, not here.
 *
 * No `update` for `branch_name` — renaming a branch in v1 is out of scope.
 * Cleanup happens transitively via `pattern_id` ON DELETE CASCADE in
 * migration 015.
 */
interface ChartBranchRepository {
    suspend fun getByPatternIdAndName(
        patternId: String,
        branchName: String,
    ): ChartBranch?

    suspend fun getByPatternId(patternId: String): List<ChartBranch>

    fun observeBranchesForPattern(patternId: String): Flow<List<ChartBranch>>

    suspend fun createBranch(branch: ChartBranch): ChartBranch

    suspend fun advanceTip(
        patternId: String,
        branchName: String,
        tipRevisionId: String,
    )

    suspend fun deleteBranch(branchId: String)
}
