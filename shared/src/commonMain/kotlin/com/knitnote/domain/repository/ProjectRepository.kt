package com.knitnote.domain.repository

import com.knitnote.domain.model.Project
import kotlinx.coroutines.flow.Flow

interface ProjectRepository {
    suspend fun getById(id: String): Project?
    suspend fun getByOwnerId(ownerId: String): List<Project>
    suspend fun getByPatternId(patternId: String): List<Project>
    fun observeByOwnerId(ownerId: String): Flow<List<Project>>
    suspend fun create(project: Project): Project
    suspend fun update(project: Project): Project
    suspend fun delete(id: String)
}
