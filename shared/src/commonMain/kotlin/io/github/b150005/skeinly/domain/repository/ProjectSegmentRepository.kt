package io.github.b150005.skeinly.domain.repository

import io.github.b150005.skeinly.domain.model.ProjectSegment
import kotlinx.coroutines.flow.Flow

/**
 * Per-project per-stitch progress.
 *
 * Absence of a row means the segment is in the implicit `todo` state. See
 * ADR-010 §2. Repository methods for state transitions:
 * - [upsert] carries wip/done writes via the sync path.
 * - [resetSegment] deletes a single segment's row — equivalent to setting
 *   state back to implicit `todo`.
 * - [resetProject] clears every segment for a project in one bulk op.
 */
interface ProjectSegmentRepository {
    fun observeByProjectId(projectId: String): Flow<List<ProjectSegment>>

    suspend fun getById(id: String): ProjectSegment?

    suspend fun getByProjectId(projectId: String): List<ProjectSegment>

    suspend fun upsert(segment: ProjectSegment): ProjectSegment

    suspend fun resetSegment(id: String)

    suspend fun resetProject(projectId: String)
}
