package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.LocalUser
import io.github.b150005.skeinly.domain.model.Project
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.ProjectRepository
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
