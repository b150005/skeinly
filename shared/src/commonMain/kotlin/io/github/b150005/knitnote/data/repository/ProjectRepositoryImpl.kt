package io.github.b150005.knitnote.data.repository

import io.github.b150005.knitnote.data.local.LocalProjectDataSource
import io.github.b150005.knitnote.data.remote.RemoteProjectDataSource
import io.github.b150005.knitnote.data.sync.SyncEntityType
import io.github.b150005.knitnote.data.sync.SyncManagerOperations
import io.github.b150005.knitnote.data.sync.SyncOperation
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ProjectRepositoryImpl(
    private val local: LocalProjectDataSource,
    private val remote: RemoteProjectDataSource?,
    private val isOnline: StateFlow<Boolean>,
    private val syncManager: SyncManagerOperations,
    private val json: Json,
) : ProjectRepository {
    override suspend fun getById(id: String): Project? {
        val localProject = local.getById(id)
        if (localProject != null || remote == null || !isOnline.value) return localProject

        return try {
            remote.getById(id)?.also { local.update(it) }
        } catch (_: Exception) {
            localProject
        }
    }

    override suspend fun getByOwnerId(ownerId: String): List<Project> {
        if (remote == null || !isOnline.value) return local.getByOwnerId(ownerId)

        return try {
            val remoteProjects = remote.getByOwnerId(ownerId)
            local.upsertAll(remoteProjects)
            remoteProjects
        } catch (_: Exception) {
            local.getByOwnerId(ownerId)
        }
    }

    override suspend fun getByPatternId(patternId: String): List<Project> = local.getByPatternId(patternId)

    override fun observeById(id: String): Flow<Project?> = local.observeById(id)

    override fun observeByOwnerId(ownerId: String): Flow<List<Project>> = local.observeByOwnerId(ownerId)

    override suspend fun create(project: Project): Project {
        local.insert(project)
        syncManager.syncOrEnqueue(SyncEntityType.PROJECT, project.id, SyncOperation.INSERT, json.encodeToString(project))
        return project
    }

    override suspend fun update(project: Project): Project {
        local.update(project)
        syncManager.syncOrEnqueue(SyncEntityType.PROJECT, project.id, SyncOperation.UPDATE, json.encodeToString(project))
        return project
    }

    override suspend fun delete(id: String) {
        local.delete(id)
        syncManager.syncOrEnqueue(SyncEntityType.PROJECT, id, SyncOperation.DELETE, "")
    }
}
