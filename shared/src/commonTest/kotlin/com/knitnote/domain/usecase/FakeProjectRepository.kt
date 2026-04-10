package com.knitnote.domain.usecase

import com.knitnote.domain.model.Project
import com.knitnote.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeProjectRepository : ProjectRepository {

    private val projects = MutableStateFlow<List<Project>>(emptyList())
    var shouldThrowOnDelete = false

    override suspend fun getById(id: String): Project? =
        projects.value.find { it.id == id }

    override suspend fun getByOwnerId(ownerId: String): List<Project> =
        projects.value.filter { it.ownerId == ownerId }

    override suspend fun getByPatternId(patternId: String): List<Project> =
        projects.value.filter { it.patternId == patternId }

    override fun observeById(id: String): Flow<Project?> =
        projects.map { list -> list.find { it.id == id } }

    override fun observeByOwnerId(ownerId: String): Flow<List<Project>> =
        projects.map { list -> list.filter { it.ownerId == ownerId } }

    override suspend fun create(project: Project): Project {
        projects.value = projects.value + project
        return project
    }

    override suspend fun update(project: Project): Project {
        projects.value = projects.value.map { if (it.id == project.id) project else it }
        return project
    }

    override suspend fun delete(id: String) {
        if (shouldThrowOnDelete) throw RuntimeException("Delete failed")
        projects.value = projects.value.filter { it.id != id }
    }
}
