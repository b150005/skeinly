package io.github.b150005.skeinly.data.repository

import io.github.b150005.skeinly.data.local.LocalProjectSegmentDataSource
import io.github.b150005.skeinly.data.sync.SyncEntityType
import io.github.b150005.skeinly.data.sync.SyncManagerOperations
import io.github.b150005.skeinly.data.sync.SyncOperation
import io.github.b150005.skeinly.domain.model.ProjectSegment
import io.github.b150005.skeinly.domain.repository.ProjectSegmentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProjectSegmentRepositoryImpl(
    private val local: LocalProjectSegmentDataSource,
    private val syncManager: SyncManagerOperations,
    private val json: Json,
) : ProjectSegmentRepository {
    override fun observeByProjectId(projectId: String): Flow<List<ProjectSegment>> = local.observeByProjectId(projectId)

    override suspend fun getById(id: String): ProjectSegment? = local.getById(id)

    override suspend fun getByProjectId(projectId: String): List<ProjectSegment> = local.getByProjectId(projectId)

    override suspend fun upsert(segment: ProjectSegment): ProjectSegment {
        local.upsert(segment)
        // INSERT used for all toggle writes. Existing coalescing rules collapse
        // rapid double-taps on the same entity id.
        syncManager.syncOrEnqueue(
            SyncEntityType.PROJECT_SEGMENT,
            segment.id,
            SyncOperation.INSERT,
            json.encodeToString(segment),
        )
        return segment
    }

    override suspend fun resetSegment(id: String) {
        local.delete(id)
        syncManager.syncOrEnqueue(SyncEntityType.PROJECT_SEGMENT, id, SyncOperation.DELETE, "")
    }

    override suspend fun resetProject(projectId: String) {
        // Enqueue a DELETE for every existing segment BEFORE wiping local state.
        // Reading after the bulk-delete would return an empty list and
        // leak orphan rows on the remote side.
        //
        // Known race: a Realtime INSERT arriving between getByProjectId() and
        // deleteByProjectId() will be deleted locally but will not receive a
        // DELETE sync entry. The remote row then re-delivers on reconnect as a
        // ghost segment. Acceptable in Phase 34 single-device scope; Phase 37
        // multi-writer design (ADR-010 §10) is the resolution surface.
        val existing = local.getByProjectId(projectId)
        local.deleteByProjectId(projectId)
        for (segment in existing) {
            syncManager.syncOrEnqueue(SyncEntityType.PROJECT_SEGMENT, segment.id, SyncOperation.DELETE, "")
        }
    }
}
