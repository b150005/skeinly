package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.Project
import io.github.b150005.skeinly.domain.repository.ProjectRepository

class GetProjectByIdUseCase(
    private val repository: ProjectRepository,
) {
    suspend operator fun invoke(id: String): UseCaseResult<Project> =
        repository
            .getById(id)
            ?.let { UseCaseResult.Success(it) }
            ?: UseCaseResult.Failure(UseCaseError.ResourceNotFound)
}
