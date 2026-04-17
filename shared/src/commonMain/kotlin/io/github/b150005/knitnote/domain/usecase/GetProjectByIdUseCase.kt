package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.repository.ProjectRepository

class GetProjectByIdUseCase(
    private val repository: ProjectRepository,
) {
    suspend operator fun invoke(id: String): UseCaseResult<Project> =
        repository
            .getById(id)
            ?.let { UseCaseResult.Success(it) }
            ?: UseCaseResult.Failure(UseCaseError.NotFound("Project not found: $id"))
}
