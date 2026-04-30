package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.Project
import io.github.b150005.skeinly.domain.model.ProjectStatus
import io.github.b150005.skeinly.domain.repository.ProjectRepository
import kotlin.time.Clock

class DecrementRowUseCase(
    private val repository: ProjectRepository,
) {
    suspend operator fun invoke(projectId: String): UseCaseResult<Project> {
        val project =
            repository.getById(projectId)
                ?: return UseCaseResult.Failure(UseCaseError.ResourceNotFound)

        if (project.currentRow <= 0) return UseCaseResult.Success(project)

        val newRow = project.currentRow - 1
        val decremented =
            project.copy(
                currentRow = newRow,
                status =
                    when {
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
