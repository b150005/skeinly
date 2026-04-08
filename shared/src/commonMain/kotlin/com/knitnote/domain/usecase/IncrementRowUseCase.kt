package com.knitnote.domain.usecase

import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import com.knitnote.domain.repository.ProjectRepository
import kotlin.time.Clock

class IncrementRowUseCase(private val repository: ProjectRepository) {

    suspend operator fun invoke(projectId: String): UseCaseResult<Project> {
        val project = repository.getById(projectId)
            ?: return UseCaseResult.Failure(UseCaseError.NotFound("Project not found: $projectId"))

        val now = Clock.System.now()
        val incremented = project.copy(
            currentRow = project.currentRow + 1,
            status = if (project.status == ProjectStatus.NOT_STARTED) {
                ProjectStatus.IN_PROGRESS
            } else {
                project.status
            },
            startedAt = project.startedAt ?: now,
            updatedAt = now,
        )
        val result = if (incremented.totalRows != null && incremented.currentRow >= incremented.totalRows) {
            incremented.copy(
                status = ProjectStatus.COMPLETED,
                completedAt = now,
            )
        } else {
            incremented
        }
        return UseCaseResult.Success(repository.update(result))
    }
}
