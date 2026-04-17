package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.Progress
import io.github.b150005.knitnote.domain.repository.ProgressRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeProgressRepository : ProgressRepository {
    private val progressNotes = MutableStateFlow<List<Progress>>(emptyList())

    override suspend fun getById(id: String): Progress? = progressNotes.value.find { it.id == id }

    override suspend fun getByProjectId(projectId: String): List<Progress> = progressNotes.value.filter { it.projectId == projectId }

    override fun observeByProjectId(projectId: String): Flow<List<Progress>> =
        progressNotes.map { list -> list.filter { it.projectId == projectId } }

    override suspend fun create(progress: Progress): Progress {
        progressNotes.value = progressNotes.value + progress
        return progress
    }

    override suspend fun delete(id: String) {
        progressNotes.value = progressNotes.value.filter { it.id != id }
    }
}
