package com.knitnote.domain.usecase

import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import com.knitnote.domain.repository.ProjectRepository
import kotlinx.datetime.Clock

class IncrementRowUseCase(private val repository: ProjectRepository) {

    suspend operator fun invoke(projectId: String): Project {
        val project = requireNotNull(repository.getById(projectId)) {
            "Project not found: $projectId"
        }
        val incremented = project.copy(
            currentRow = project.currentRow + 1,
            status = if (project.status == ProjectStatus.NOT_STARTED) {
                ProjectStatus.IN_PROGRESS
            } else {
                project.status
            },
            startedAt = project.startedAt ?: Clock.System.now(),
        )
        val result = if (incremented.totalRows != null && incremented.currentRow >= incremented.totalRows) {
            incremented.copy(
                status = ProjectStatus.COMPLETED,
                completedAt = Clock.System.now(),
            )
        } else {
            incremented
        }
        return repository.update(result)
    }
}
