package io.github.b150005.knitnote.data.repository

import io.github.b150005.knitnote.data.local.LocalProgressDataSource
import io.github.b150005.knitnote.data.remote.RemoteProgressDataSource
import io.github.b150005.knitnote.data.sync.SyncEntityType
import io.github.b150005.knitnote.data.sync.SyncManagerOperations
import io.github.b150005.knitnote.data.sync.SyncOperation
import io.github.b150005.knitnote.domain.model.Progress
import io.github.b150005.knitnote.domain.repository.ProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProgressRepositoryImpl(
    private val local: LocalProgressDataSource,
    private val remote: RemoteProgressDataSource?,
    private val isOnline: StateFlow<Boolean>,
    private val syncManager: SyncManagerOperations,
    private val json: Json,
) : ProgressRepository {
    override suspend fun getById(id: String): Progress? = local.getById(id)

    override suspend fun getByProjectId(projectId: String): List<Progress> = local.getByProjectId(projectId)

    override fun observeByProjectId(projectId: String): Flow<List<Progress>> = local.observeByProjectId(projectId)

    override suspend fun create(progress: Progress): Progress {
        local.insert(progress)
        syncManager.syncOrEnqueue(SyncEntityType.PROGRESS, progress.id, SyncOperation.INSERT, json.encodeToString(progress))
        return progress
    }

    override suspend fun delete(id: String) {
        local.delete(id)
        syncManager.syncOrEnqueue(SyncEntityType.PROGRESS, id, SyncOperation.DELETE, "")
    }
}
