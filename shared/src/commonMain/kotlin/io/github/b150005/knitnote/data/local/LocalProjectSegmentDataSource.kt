package io.github.b150005.knitnote.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import io.github.b150005.knitnote.data.mapper.toDbString
import io.github.b150005.knitnote.data.mapper.toDomain
import io.github.b150005.knitnote.db.KnitNoteDatabase
import io.github.b150005.knitnote.domain.model.ProjectSegment
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class LocalProjectSegmentDataSource(
    private val db: KnitNoteDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val queries get() = db.projectSegmentQueries

    suspend fun getById(id: String): ProjectSegment? =
        withContext(ioDispatcher) {
            queries.getById(id).executeAsOneOrNull()?.toDomain()
        }

    suspend fun getByProjectId(projectId: String): List<ProjectSegment> =
        withContext(ioDispatcher) {
            queries.getByProjectId(projectId).executeAsList().map { it.toDomain() }
        }

    fun observeByProjectId(projectId: String): Flow<List<ProjectSegment>> =
        queries
            .observeByProjectId(projectId)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { list -> list.map { it.toDomain() } }

    suspend fun upsert(segment: ProjectSegment): ProjectSegment =
        withContext(ioDispatcher) {
            queries.upsert(
                id = segment.id,
                project_id = segment.projectId,
                layer_id = segment.layerId,
                cell_x = segment.cellX.toLong(),
                cell_y = segment.cellY.toLong(),
                state = segment.state.toDbString(),
                updated_at = segment.updatedAt.toString(),
                owner_id = segment.ownerId,
            )
            segment
        }

    suspend fun delete(id: String): Unit =
        withContext(ioDispatcher) {
            queries.deleteById(id)
        }

    suspend fun deleteByProjectId(projectId: String): Unit =
        withContext(ioDispatcher) {
            queries.deleteByProjectId(projectId)
        }
}
