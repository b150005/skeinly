package com.knitnote.domain.usecase

import com.knitnote.domain.LocalUser
import com.knitnote.domain.model.Project
import com.knitnote.domain.repository.AuthRepository
import com.knitnote.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.Flow

class GetProjectsUseCase(
    private val repository: ProjectRepository,
    private val authRepository: AuthRepository,
) {
    operator fun invoke(): Flow<List<Project>> {
        val ownerId = authRepository.getCurrentUserId() ?: LocalUser.ID
        return repository.observeByOwnerId(ownerId)
    }
}
