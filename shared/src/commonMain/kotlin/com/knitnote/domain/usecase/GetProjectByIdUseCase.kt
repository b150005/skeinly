package com.knitnote.domain.usecase

import com.knitnote.domain.model.Project
import com.knitnote.domain.repository.ProjectRepository

class GetProjectByIdUseCase(private val repository: ProjectRepository) {

    suspend operator fun invoke(id: String): UseCaseResult<Project> =
        repository.getById(id)
            ?.let { UseCaseResult.Success(it) }
            ?: UseCaseResult.Failure(UseCaseError.NotFound("Project not found: $id"))
}
