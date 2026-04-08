package com.knitnote.data.repository

import com.knitnote.data.local.LocalProjectDataSource
import com.knitnote.data.remote.RemoteProjectDataSource
import com.knitnote.domain.model.Project
import com.knitnote.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

class ProjectRepositoryImpl(
    private val local: LocalProjectDataSource,
    private val remote: RemoteProjectDataSource?,
    private val isOnline: StateFlow<Boolean>,
) : ProjectRepository {

    override suspend fun getById(id: String): Project? {
        // Try local first
        val localProject = local.getById(id)
        if (localProject != null || remote == null || !isOnline.value) return localProject

        // Refresh from remote
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

    override suspend fun getByPatternId(patternId: String): List<Project> =
        local.getByPatternId(patternId)

    override fun observeById(id: String): Flow<Project?> =
        local.observeById(id)

    override fun observeByOwnerId(ownerId: String): Flow<List<Project>> =
        local.observeByOwnerId(ownerId)

    override suspend fun create(project: Project): Project {
        // Always write to local
        local.insert(project)

        // If online and remote available, also write to remote
        if (remote != null && isOnline.value) {
            try {
                remote.insert(project)
            } catch (_: Exception) {
                // MVP: silent fail for remote, data is in local
            }
        }
        return project
    }

    override suspend fun update(project: Project): Project {
        local.update(project)

        if (remote != null && isOnline.value) {
            try {
                remote.update(project)
            } catch (_: Exception) {
                // MVP: silent fail
            }
        }
        return project
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
