package com.knitnote.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.knitnote.data.mapper.toDomain
import com.knitnote.db.KnitNoteDatabase
import com.knitnote.domain.model.Progress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class LocalProgressDataSource(
    private val db: KnitNoteDatabase,
) {

    private val queries get() = db.progressQueries

    suspend fun getById(id: String): Progress? = withContext(Dispatchers.IO) {
        queries.getById(id).executeAsOneOrNull()?.toDomain()
    }

    suspend fun getByProjectId(projectId: String): List<Progress> = withContext(Dispatchers.IO) {
        queries.getByProjectId(projectId).executeAsList().map { it.toDomain() }
    }

    fun observeByProjectId(projectId: String): Flow<List<Progress>> =
        queries.getByProjectId(projectId)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { list -> list.map { it.toDomain() } }

    suspend fun insert(progress: Progress): Progress = withContext(Dispatchers.IO) {
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

    suspend fun upsert(progress: Progress): Unit = withContext(Dispatchers.IO) {
        db.transaction {
            val exists = queries.getById(progress.id).executeAsOneOrNull() != null
            if (exists) {
                queries.deleteById(progress.id)
            }
            queries.insert(
                id = progress.id,
                project_id = progress.projectId,
                row_number = progress.rowNumber.toLong(),
                photo_url = progress.photoUrl,
                note = progress.note,
                created_at = progress.createdAt.toString(),
            )
        }
    }

    suspend fun delete(id: String): Unit = withContext(Dispatchers.IO) {
        queries.deleteById(id)
    }
}
