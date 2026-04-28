package io.github.b150005.knitnote.test

import io.github.b150005.knitnote.domain.model.Progress
import io.github.b150005.knitnote.domain.repository.ProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeProgressRepository : ProgressRepository {
    private val progressList = MutableStateFlow<List<Progress>>(emptyList())

    override suspend fun getById(id: String): Progress? = progressList.value.find { it.id == id }

    override suspend fun getByProjectId(projectId: String): List<Progress> = progressList.value.filter { it.projectId == projectId }

    override fun observeByProjectId(projectId: String): Flow<List<Progress>> =
        progressList.map { list -> list.filter { it.projectId == projectId } }

    override suspend fun create(progress: Progress): Progress {
        progressList.value = progressList.value + progress
        return progress
    }

    override suspend fun delete(id: String) {
        progressList.value = progressList.value.filter { it.id != id }
    }
}
