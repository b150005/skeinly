package com.knitnote.data.sync

import com.knitnote.domain.model.Project

class FakeRemoteProjectDataSource : RemoteProjectSyncOperations {
    val insertedProjects = mutableListOf<Project>()
    val updatedProjects = mutableListOf<Project>()
    val deletedIds = mutableListOf<String>()
    var shouldFail = false

    override suspend fun insert(project: Project): Project {
        if (shouldFail) throw RuntimeException("Remote insert failed")
        insertedProjects.add(project)
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
