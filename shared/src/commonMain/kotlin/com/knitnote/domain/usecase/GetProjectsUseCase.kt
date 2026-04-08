package com.knitnote.domain.usecase

import com.knitnote.domain.LocalUser
import com.knitnote.domain.model.Project
import com.knitnote.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.Flow

class GetProjectsUseCase(private val repository: ProjectRepository) {
    operator fun invoke(): Flow<List<Project>> =
        repository.observeByOwnerId(LocalUser.ID)
}
