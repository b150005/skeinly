package com.knitnote.domain.usecase

import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import com.knitnote.domain.repository.ProjectRepository
import kotlinx.datetime.Clock

class DecrementRowUseCase(private val repository: ProjectRepository) {

    suspend operator fun invoke(projectId: String): UseCaseResult<Project> {
        val project = repository.getById(projectId)
            ?: return UseCaseResult.Failure(UseCaseError.NotFound("Project not found: $projectId"))

        if (project.currentRow <= 0) return UseCaseResult.Success(project)

        val newRow = project.currentRow - 1
        val decremented = project.copy(
            currentRow = newRow,
            status = when {
                newRow == 0 -> ProjectStatus.NOT_STARTED
                else -> ProjectStatus.IN_PROGRESS
            },
            startedAt = if (newRow == 0) null else project.startedAt,
            completedAt = null,
            updatedAt = Clock.System.now(),
        )
        return UseCaseResult.Success(repository.update(decremented))
    }
}
