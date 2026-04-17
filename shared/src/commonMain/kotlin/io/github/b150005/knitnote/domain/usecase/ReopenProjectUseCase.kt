package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectStatus
import io.github.b150005.knitnote.domain.repository.ProjectRepository
import kotlin.time.Clock

class ReopenProjectUseCase(
    private val repository: ProjectRepository,
) {
    suspend operator fun invoke(projectId: String): UseCaseResult<Project> {
        val project =
            repository.getById(projectId)
                ?: return UseCaseResult.Failure(UseCaseError.NotFound("Project not found: $projectId"))

        if (project.status != ProjectStatus.COMPLETED) {
            return UseCaseResult.Success(project)
        }

        val reopened =
            project.copy(
                status = if (project.currentRow > 0) ProjectStatus.IN_PROGRESS else ProjectStatus.NOT_STARTED,
                completedAt = null,
                updatedAt = Clock.System.now(),
            )
        return UseCaseResult.Success(repository.update(reopened))
    }
}
