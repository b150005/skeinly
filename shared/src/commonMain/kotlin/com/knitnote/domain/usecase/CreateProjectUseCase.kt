package com.knitnote.domain.usecase

import com.knitnote.domain.LocalUser
import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import com.knitnote.domain.repository.AuthRepository
import com.knitnote.domain.repository.ProjectRepository
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CreateProjectUseCase(
    private val repository: ProjectRepository,
    private val authRepository: AuthRepository,
) {

    @OptIn(ExperimentalUuidApi::class)
    suspend operator fun invoke(title: String, totalRows: Int?): UseCaseResult<Project> {
        if (title.isBlank()) {
            return UseCaseResult.Failure(UseCaseError.Validation("Title must not be blank"))
        }
        val now = Clock.System.now()
        val ownerId = authRepository.getCurrentUserId() ?: LocalUser.ID
        val project = Project(
            id = Uuid.random().toString(),
            ownerId = ownerId,
            patternId = LocalUser.DEFAULT_PATTERN_ID,
            title = title,
            status = ProjectStatus.NOT_STARTED,
            currentRow = 0,
            totalRows = totalRows,
            startedAt = null,
            completedAt = null,
            createdAt = now,
            updatedAt = now,
        )
        return UseCaseResult.Success(repository.create(project))
    }
}
