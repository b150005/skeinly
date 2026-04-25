package io.github.b150005.knitnote.domain.repository

import io.github.b150005.knitnote.domain.model.ChartRevision
import kotlinx.coroutines.flow.Flow

/**
 * Append-only history of chart revisions per ADR-013 §1.
 *
 * Phase 37.1 reads:
 * - [getRevision] resolves a single revision by its commit identifier; used by
 *   [io.github.b150005.knitnote.domain.usecase.GetChartDiffUseCase] (Phase 37.3) and
 *   the diff screen's load path.
 * - [getHistoryForPattern] / [observeHistoryForPattern] feed
 *   `ChartHistoryViewModel` (Phase 37.2) with newest-first revision lists.
 *
 * Phase 37.1 writes:
 * - [append] inserts a new immutable revision row. Called by
 *   `StructuredChartRepository.update` BEFORE its tip update; the revision
 *   row is part of the same logical save and is queued through PendingSync
 *   in lock-step with the tip.
 *
 * No `update` or `delete` — revisions are immutable once written, mirroring
 * Git's append-only history invariant. Cleanup happens transitively via
 * `pattern_id` ON DELETE CASCADE in migration 015.
 */
interface ChartRevisionRepository {
    suspend fun getRevision(revisionId: String): ChartRevision?

    suspend fun getHistoryForPattern(
        patternId: String,
        limit: Int = DEFAULT_LIMIT,
        offset: Int = 0,
    ): List<ChartRevision>

    fun observeHistoryForPattern(patternId: String): Flow<List<ChartRevision>>

    suspend fun append(revision: ChartRevision): ChartRevision

    companion object {
        const val DEFAULT_LIMIT: Int = 50
    }
}
