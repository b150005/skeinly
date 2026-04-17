package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.ActivityTargetType
import io.github.b150005.knitnote.domain.model.ActivityType
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectStatus
import io.github.b150005.knitnote.domain.model.SharePermission
import io.github.b150005.knitnote.domain.model.ShareStatus
import io.github.b150005.knitnote.domain.model.Visibility
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.PatternRepository
import io.github.b150005.knitnote.domain.repository.ProjectRepository
import io.github.b150005.knitnote.domain.repository.ShareRepository
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

data class ForkedProject(
    val pattern: Pattern,
    val project: Project,
)

class ForkSharedPatternUseCase(
    private val shareRepository: ShareRepository?,
    private val patternRepository: PatternRepository,
    private val projectRepository: ProjectRepository,
    private val authRepository: AuthRepository,
    private val createActivity: CreateActivityUseCase? = null,
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend operator fun invoke(shareId: String): UseCaseResult<ForkedProject> {
        if (shareRepository == null) {
            return UseCaseResult.Failure(UseCaseError.Validation("Sharing requires cloud connectivity"))
        }

        val userId =
            authRepository.getCurrentUserId()
                ?: return UseCaseResult.Failure(UseCaseError.Validation("Must be signed in to fork"))

        val share =
            shareRepository.getById(shareId)
                ?: return UseCaseResult.Failure(UseCaseError.NotFound("Share not found"))

        // Verify ownership: only the intended recipient can fork
        if (share.toUserId != null && share.toUserId != userId) {
            return UseCaseResult.Failure(UseCaseError.Validation("This share does not belong to you"))
        }

        // Verify status: declined shares cannot be forked
        if (share.status == ShareStatus.DECLINED) {
            return UseCaseResult.Failure(UseCaseError.Validation("This share has been declined"))
        }

        if (share.permission != SharePermission.FORK) {
            return UseCaseResult.Failure(UseCaseError.Validation("This share does not allow forking"))
        }

        val sourcePattern =
            patternRepository.getById(share.patternId)
                ?: return UseCaseResult.Failure(UseCaseError.NotFound("Shared pattern not found"))

        val now = Clock.System.now()

        val forkedPattern =
            sourcePattern.copy(
                id = Uuid.random().toString(),
                ownerId = userId,
                visibility = Visibility.PRIVATE,
                createdAt = now,
                updatedAt = now,
            )
        patternRepository.create(forkedPattern)

        val forkedProject =
            Project(
                id = Uuid.random().toString(),
                ownerId = userId,
                patternId = forkedPattern.id,
                title = forkedPattern.title,
                status = ProjectStatus.NOT_STARTED,
                currentRow = 0,
                totalRows = null,
                startedAt = null,
                completedAt = null,
                createdAt = now,
                updatedAt = now,
            )
        projectRepository.create(forkedProject)

        createActivity?.invoke(
            userId = userId,
            type = ActivityType.FORKED,
            targetType = ActivityTargetType.PATTERN,
            targetId = forkedPattern.id,
        )

        return UseCaseResult.Success(ForkedProject(pattern = forkedPattern, project = forkedProject))
    }
}
