package com.knitnote.data.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.knitnote.data.mapper.toDomain
import com.knitnote.db.KnitNoteDatabase
import com.knitnote.domain.model.Progress
import com.knitnote.domain.repository.ProgressRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class ProgressRepositoryImpl(
    private val db: KnitNoteDatabase,
) : ProgressRepository {

    private val queries get() = db.progressQueries

    override suspend fun getById(id: String): Progress? = withContext(Dispatchers.IO) {
        queries.getById(id).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun getByProjectId(projectId: String): List<Progress> = withContext(Dispatchers.IO) {
        queries.getByProjectId(projectId).executeAsList().map { it.toDomain() }
    }

    override fun observeByProjectId(projectId: String): Flow<List<Progress>> =
        queries.getByProjectId(projectId)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list -> list.map { it.toDomain() } }

    override suspend fun create(progress: Progress): Progress = withContext(Dispatchers.IO) {
        queries.insert(
            id = progress.id,
            project_id = progress.projectId,
            row_number = progress.rowNumber.toLong(),
            photo_url = progress.photoUrl,
            note = progress.note,
            created_at = progress.createdAt.toString(),
        )
        progress
    }

    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        queries.deleteById(id)
    }
}
