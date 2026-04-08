package com.knitnote.data.repository

import com.knitnote.data.local.LocalProgressDataSource
import com.knitnote.data.remote.RemoteProgressDataSource
import com.knitnote.domain.model.Progress
import com.knitnote.domain.repository.ProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class ProgressRepositoryImpl(
    private val local: LocalProgressDataSource,
    private val remote: RemoteProgressDataSource?,
    private val isOnline: StateFlow<Boolean>,
) : ProgressRepository {

    override suspend fun getById(id: String): Progress? =
        local.getById(id)

    override suspend fun getByProjectId(projectId: String): List<Progress> =
        local.getByProjectId(projectId)

    override fun observeByProjectId(projectId: String): Flow<List<Progress>> =
        local.observeByProjectId(projectId)

    override suspend fun create(progress: Progress): Progress {
        local.insert(progress)

        if (remote != null && isOnline.value) {
            try {
                remote.insert(progress)
            } catch (_: Exception) {
                // MVP: silent fail
            }
        }
        return progress
    }

    override suspend fun delete(id: String) {
        local.delete(id)

        if (remote != null && isOnline.value) {
            try {
                remote.delete(id)
            } catch (_: Exception) {
                // MVP: silent fail
            }
        }
    }
}
