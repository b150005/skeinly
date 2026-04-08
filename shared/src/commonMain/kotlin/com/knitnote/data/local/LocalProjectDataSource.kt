package com.knitnote.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.knitnote.data.mapper.toDomain
import com.knitnote.data.mapper.toDbString
import com.knitnote.db.KnitNoteDatabase
import com.knitnote.domain.model.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class LocalProjectDataSource(
    private val db: KnitNoteDatabase,
) {

    private val queries get() = db.projectQueries

    suspend fun getById(id: String): Project? = withContext(Dispatchers.IO) {
        queries.getById(id).executeAsOneOrNull()?.toDomain()
    }

    suspend fun getByOwnerId(ownerId: String): List<Project> = withContext(Dispatchers.IO) {
        queries.getByOwnerId(ownerId).executeAsList().map { it.toDomain() }
    }

    suspend fun getByPatternId(patternId: String): List<Project> = withContext(Dispatchers.IO) {
        queries.getByPatternId(patternId).executeAsList().map { it.toDomain() }
    }

    fun observeById(id: String): Flow<Project?> =
        queries.observeById(id)
            .asFlow()
            .mapToOneOrNull(Dispatchers.IO)
            .map { it?.toDomain() }

    fun observeByOwnerId(ownerId: String): Flow<List<Project>> =
        queries.getByOwnerId(ownerId)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list -> list.map { it.toDomain() } }

    suspend fun insert(project: Project): Project = withContext(Dispatchers.IO) {
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
            updated_at = project.updatedAt.toString(),
        )
        project
    }

    suspend fun update(project: Project): Project = withContext(Dispatchers.IO) {
        queries.update(
            title = project.title,
            status = project.status.toDbString(),
            current_row = project.currentRow.toLong(),
            total_rows = project.totalRows?.toLong(),
            started_at = project.startedAt?.toString(),
            completed_at = project.completedAt?.toString(),
            updated_at = project.updatedAt.toString(),
            id = project.id,
        )
        project
    }

    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        queries.deleteById(id)
    }

    suspend fun upsertAll(projects: List<Project>) = withContext(Dispatchers.IO) {
        db.transaction {
            projects.forEach { project ->
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
                    updated_at = project.updatedAt.toString(),
                )
            }
        }
    }
}
