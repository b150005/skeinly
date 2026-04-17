package io.github.b150005.knitnote.domain.repository

import io.github.b150005.knitnote.domain.model.Progress
import kotlinx.coroutines.flow.Flow

interface ProgressRepository {
    suspend fun getById(id: String): Progress?

    suspend fun getByProjectId(projectId: String): List<Progress>

    fun observeByProjectId(projectId: String): Flow<List<Progress>>

    suspend fun create(progress: Progress): Progress

    suspend fun delete(id: String)
}
