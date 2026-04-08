package com.knitnote.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.knitnote.data.mapper.toDomain
import com.knitnote.data.mapper.toDbString
import com.knitnote.db.KnitNoteDatabase
import com.knitnote.domain.model.Project
import com.knitnote.domain.repository.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ProjectRepositoryImpl(
    private val db: KnitNoteDatabase,
) : ProjectRepository {

    private val queries get() = db.projectQueries

    override suspend fun getById(id: String): Project? = withContext(Dispatchers.IO) {
        queries.getById(id).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun getByOwnerId(ownerId: String): List<Project> = withContext(Dispatchers.IO) {
        queries.getByOwnerId(ownerId).executeAsList().map { it.toDomain() }
    }

    override suspend fun getByPatternId(patternId: String): List<Project> = withContext(Dispatchers.IO) {
        queries.getByPatternId(patternId).executeAsList().map { it.toDomain() }
    }

    override fun observeById(id: String): Flow<Project?> =
        queries.observeById(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { it?.toDomain() }

    override fun observeByOwnerId(ownerId: String): Flow<List<Project>> =
        queries.getByOwnerId(ownerId)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list -> list.map { it.toDomain() } }

    override suspend fun create(project: Project): Project = withContext(Dispatchers.IO) {
        queries.insert(
            id = project.id,
            owner_id = project.ownerId,
            pattern_id = project.patternId,
            title = project.title,
            status = project.status.toDbString(),
            current_row = project.currentRow.toLong(),
            total_rows = project.totalRows?.toLong(),
            started_at = project.startedAt?.toString(),
            completed_at = project.completedAt?.toString(),
            created_at = project.createdAt.toString(),
        )
        project
    }

    override suspend fun update(project: Project): Project = withContext(Dispatchers.IO) {
        queries.update(
            title = project.title,
            status = project.status.toDbString(),
            current_row = project.currentRow.toLong(),
            total_rows = project.totalRows?.toLong(),
            started_at = project.startedAt?.toString(),
            completed_at = project.completedAt?.toString(),
            id = project.id,
        )
        project
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        queries.deleteById(id)
    }
}
