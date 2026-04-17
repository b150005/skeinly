package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.LocalUser
import io.github.b150005.knitnote.domain.model.ActivityTargetType
import io.github.b150005.knitnote.domain.model.ActivityType
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectStatus
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.ProjectRepository
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CreateProjectUseCase(
    private val repository: ProjectRepository,
    private val authRepository: AuthRepository,
    private val createActivity: CreateActivityUseCase? = null,
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend operator fun invoke(
        title: String,
        totalRows: Int?,
        patternId: String? = null,
    ): UseCaseResult<Project> {
        if (title.isBlank()) {
            return UseCaseResult.Failure(UseCaseError.Validation("Title must not be blank"))
        }
        val now = Clock.System.now()
        val ownerId = authRepository.getCurrentUserId() ?: LocalUser.ID
        val project =
            Project(
                id = Uuid.random().toString(),
                ownerId = ownerId,
                patternId = patternId ?: LocalUser.DEFAULT_PATTERN_ID,
                title = title,
                status = ProjectStatus.NOT_STARTED,
                currentRow = 0,
                totalRows = totalRows,
                startedAt = null,
                completedAt = null,
                createdAt = now,
                updatedAt = now,
            )
        val created = repository.create(project)

        if (ownerId != LocalUser.ID) {
            createActivity?.invoke(
                userId = ownerId,
                type = ActivityType.STARTED,
                targetType = ActivityTargetType.PROJECT,
                targetId = created.id,
            )
        }

        return UseCaseResult.Success(created)
    }
}
