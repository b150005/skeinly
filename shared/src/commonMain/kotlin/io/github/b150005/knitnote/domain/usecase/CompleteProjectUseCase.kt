package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.ActivityTargetType
import io.github.b150005.knitnote.domain.model.ActivityType
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectStatus
import io.github.b150005.knitnote.domain.repository.ProjectRepository
import kotlin.time.Clock

class CompleteProjectUseCase(
    private val repository: ProjectRepository,
    private val createActivity: CreateActivityUseCase? = null,
) {
    suspend operator fun invoke(projectId: String): UseCaseResult<Project> {
        val project =
            repository.getById(projectId)
                ?: return UseCaseResult.Failure(UseCaseError.NotFound("Project not found: $projectId"))

        if (project.status == ProjectStatus.COMPLETED) {
            return UseCaseResult.Success(project)
        }

        val now = Clock.System.now()
        val completed =
            project.copy(
                status = ProjectStatus.COMPLETED,
                completedAt = now,
                updatedAt = now,
            )
        val updated = repository.update(completed)

        createActivity?.invoke(
            userId = project.ownerId,
            type = ActivityType.COMPLETED,
            targetType = ActivityTargetType.PROJECT,
            targetId = projectId,
        )

        return UseCaseResult.Success(updated)
    }
}
