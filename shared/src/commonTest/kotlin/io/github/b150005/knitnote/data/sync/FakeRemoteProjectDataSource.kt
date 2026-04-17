package io.github.b150005.knitnote.data.sync

import io.github.b150005.knitnote.domain.model.Project

class FakeRemoteProjectDataSource : RemoteProjectSyncOperations {
    val upsertedProjects = mutableListOf<Project>()
    val updatedProjects = mutableListOf<Project>()
    val deletedIds = mutableListOf<String>()
    var shouldFail = false

    override suspend fun upsert(project: Project): Project {
        if (shouldFail) throw RuntimeException("Remote upsert failed")
        upsertedProjects.add(project)
        return project
    }

    override suspend fun update(project: Project): Project {
        if (shouldFail) throw RuntimeException("Remote update failed")
        updatedProjects.add(project)
        return project
    }

    override suspend fun delete(id: String) {
        if (shouldFail) throw RuntimeException("Remote delete failed")
        deletedIds.add(id)
    }
}
