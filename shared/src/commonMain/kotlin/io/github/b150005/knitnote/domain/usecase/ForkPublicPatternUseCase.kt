package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.ActivityTargetType
import io.github.b150005.knitnote.domain.model.ActivityType
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectStatus
import io.github.b150005.knitnote.domain.model.Visibility
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.PatternRepository
import io.github.b150005.knitnote.domain.repository.ProjectRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class ForkPublicPatternUseCase(
    private val patternRepository: PatternRepository,
    private val projectRepository: ProjectRepository,
    private val authRepository: AuthRepository,
    private val createActivity: CreateActivityUseCase? = null,
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend operator fun invoke(patternId: String): UseCaseResult<ForkedProject> {
        val userId =
            authRepository.getCurrentUserId()
                ?: return UseCaseResult.Failure(UseCaseError.Validation("Must be signed in to fork"))

        val sourcePattern =
            patternRepository.getById(patternId)
                ?: return UseCaseResult.Failure(UseCaseError.NotFound("Pattern not found"))

        if (sourcePattern.visibility != Visibility.PUBLIC) {
            return UseCaseResult.Failure(UseCaseError.Validation("Only public patterns can be forked"))
        }

        val now = Clock.System.now()

        val forkedPattern =
            sourcePattern.copy(
                id = Uuid.random().toString(),
                ownerId = userId,
                visibility = Visibility.PRIVATE,
                createdAt = now,
                updatedAt = now,
            )

        return try {
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

            UseCaseResult.Success(ForkedProject(pattern = forkedPattern, project = forkedProject))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }
}
