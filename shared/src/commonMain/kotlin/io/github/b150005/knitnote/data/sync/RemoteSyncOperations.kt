package io.github.b150005.knitnote.data.sync

import io.github.b150005.knitnote.domain.model.ChartBranch
import io.github.b150005.knitnote.domain.model.ChartRevision
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Progress
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectSegment
import io.github.b150005.knitnote.domain.model.StructuredChart

/**
 * Interface for project remote write operations needed by the sync system.
 */
interface RemoteProjectSyncOperations {
    suspend fun upsert(project: Project): Project

    suspend fun update(project: Project): Project

    suspend fun delete(id: String)
}

/**
 * Interface for progress remote write operations needed by the sync system.
 */
interface RemoteProgressSyncOperations {
    suspend fun upsert(progress: Progress): Progress

    suspend fun delete(id: String)
}

/**
 * Interface for pattern remote write operations needed by the sync system.
 */
interface RemotePatternSyncOperations {
    suspend fun upsert(pattern: Pattern): Pattern

    suspend fun update(pattern: Pattern): Pattern

    suspend fun delete(id: String)
}

/**
 * Interface for structured chart remote write operations needed by the sync system.
 */
interface RemoteStructuredChartSyncOperations {
    suspend fun upsert(chart: StructuredChart): StructuredChart

    suspend fun update(chart: StructuredChart): StructuredChart

    suspend fun delete(id: String)
}

/**
 * Interface for project segment remote write operations needed by the sync system.
 *
 * Segment toggle → UPSERT; reset-to-todo → DELETE. No separate UPDATE path —
 * wip→done reuses UPSERT idempotently per ADR-010 §5.
 */
interface RemoteProjectSegmentSyncOperations {
    suspend fun upsert(segment: ProjectSegment): ProjectSegment

    suspend fun delete(id: String)
}

/**
 * Interface for chart revision remote write operations needed by the sync system.
 *
 * Revisions are immutable per ADR-013 §1 — only `append` (INSERT) is supported.
 * No UPDATE or DELETE path exists; the row's lifecycle ends only when the
 * parent pattern is deleted (ON DELETE CASCADE in migration 015).
 */
interface RemoteChartRevisionSyncOperations {
    suspend fun append(revision: ChartRevision): ChartRevision
}

/**
 * Interface for chart branch remote write operations needed by the sync system.
 *
 * Phase 37.1 only writes via `upsert` (idempotent default-"main" creation).
 * Phase 37.4 will additionally exercise UPDATE (tip movement on save) and
 * DELETE (branch deletion); the interface surfaces both today so the sync
 * routing in [SyncExecutor] is wired exhaustively from this slice.
 */
interface RemoteChartBranchSyncOperations {
    suspend fun upsert(branch: ChartBranch): ChartBranch

    suspend fun delete(id: String)
}
