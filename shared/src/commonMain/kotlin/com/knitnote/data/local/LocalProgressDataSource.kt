package com.knitnote.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.knitnote.data.mapper.toDomain
import com.knitnote.db.KnitNoteDatabase
import com.knitnote.domain.model.Progress
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class LocalProgressDataSource(
    private val db: KnitNoteDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private val queries get() = db.progressQueries

    suspend fun getById(id: String): Progress? =
        withContext(ioDispatcher) {
            queries.getById(id).executeAsOneOrNull()?.toDomain()
        }

    suspend fun getByProjectId(projectId: String): List<Progress> =
        withContext(ioDispatcher) {
            queries.getByProjectId(projectId).executeAsList().map { it.toDomain() }
        }

    fun observeByProjectId(projectId: String): Flow<List<Progress>> =
        queries
            .getByProjectId(projectId)
            .asFlow()
            .mapToList(ioDispatcher)
            .map { list -> list.map { it.toDomain() } }

    suspend fun insert(progress: Progress): Progress =
        withContext(ioDispatcher) {
            queries.insert(
                id = progress.id,
                project_id = progress.projectId,
                row_number = progress.rowNumber.toLong(),
                photo_url = progress.photoUrl,
                note = progress.note,
                created_at = progress.createdAt.toString(),
                owner_id = progress.ownerId,
            )
            progress
        }

    suspend fun upsert(progress: Progress): Unit =
        withContext(ioDispatcher) {
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
                    owner_id = progress.ownerId,
                )
            }
        }

    suspend fun delete(id: String): Unit =
        withContext(ioDispatcher) {
            queries.deleteById(id)
        }
}
