package io.github.b150005.knitnote.android.test

import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeProjectRepository : ProjectRepository {
    private val projects = MutableStateFlow<List<Project>>(emptyList())

    override suspend fun getById(id: String): Project? = projects.value.find { it.id == id }

    override suspend fun getByOwnerId(ownerId: String): List<Project> = projects.value.filter { it.ownerId == ownerId }

    override suspend fun getByPatternId(patternId: String): List<Project> = projects.value.filter { it.patternId == patternId }

    override fun observeById(id: String): Flow<Project?> = projects.map { list -> list.find { it.id == id } }

    override fun observeByOwnerId(ownerId: String): Flow<List<Project>> = projects.map { list -> list.filter { it.ownerId == ownerId } }

    override suspend fun create(project: Project): Project {
        projects.value = projects.value + project
        return project
    }

    override suspend fun update(project: Project): Project {
        projects.value = projects.value.map { if (it.id == project.id) project else it }
        return project
    }

    override suspend fun delete(id: String) {
        projects.value = projects.value.filter { it.id != id }
    }

    fun addProject(project: Project) {
        projects.value = projects.value + project
    }
}
