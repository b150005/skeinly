package com.knitnote.data.repository

import com.knitnote.data.local.LocalProgressDataSource
import com.knitnote.data.remote.RemoteProgressDataSource
import com.knitnote.data.sync.SyncManagerOperations
import com.knitnote.domain.model.Progress
import com.knitnote.domain.repository.ProgressRepository
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

    override suspend fun getById(id: String): Progress? =
        local.getById(id)

    override suspend fun getByProjectId(projectId: String): List<Progress> =
        local.getByProjectId(projectId)

    override fun observeByProjectId(projectId: String): Flow<List<Progress>> =
        local.observeByProjectId(projectId)

    override suspend fun create(progress: Progress): Progress {
        local.insert(progress)
        syncManager.syncOrEnqueue("progress", progress.id, "insert", json.encodeToString(progress))
        return progress
    }

    override suspend fun delete(id: String) {
        local.delete(id)
        syncManager.syncOrEnqueue("progress", id, "delete", "")
    }
}
