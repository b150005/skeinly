package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectStatus
import io.github.b150005.knitnote.domain.repository.ProjectRepository
import kotlin.time.Clock

class UpdateProjectUseCase(
    private val repository: ProjectRepository,
) {
    suspend operator fun invoke(
        projectId: String,
        title: String,
        totalRows: Int?,
    ): UseCaseResult<Project> {
        if (title.isBlank()) {
            return UseCaseResult.Failure(UseCaseError.Validation("Title must not be blank"))
        }
        val project =
            repository.getById(projectId)
                ?: return UseCaseResult.Failure(UseCaseError.NotFound("Project not found: $projectId"))

        val newStatus = resolveStatus(project, totalRows)
        val updated =
            project.copy(
                title = title,
                totalRows = totalRows,
                status = newStatus,
                completedAt =
                    if (newStatus == ProjectStatus.COMPLETED && project.completedAt == null) {
                        Clock.System.now()
                    } else if (newStatus != ProjectStatus.COMPLETED) {
                        null
                    } else {
                        project.completedAt
                    },
                updatedAt = Clock.System.now(),
            )
        return UseCaseResult.Success(repository.update(updated))
    }

    private fun resolveStatus(
        project: Project,
        newTotalRows: Int?,
    ): ProjectStatus {
        if (newTotalRows != null && project.currentRow >= newTotalRows) {
            return ProjectStatus.COMPLETED
        }
        if (project.status == ProjectStatus.COMPLETED && newTotalRows != null && project.currentRow < newTotalRows) {
            return ProjectStatus.IN_PROGRESS
        }
        return project.status
    }
}
