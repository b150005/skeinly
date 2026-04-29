package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.ActivityTargetType
import io.github.b150005.knitnote.domain.model.ActivityType
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectStatus
import io.github.b150005.knitnote.domain.model.Visibility
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.PatternRepository
import io.github.b150005.knitnote.domain.repository.ProjectRepository
import io.github.b150005.knitnote.domain.repository.StructuredChartRepository
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class ForkPublicPatternUseCase(
    private val patternRepository: PatternRepository,
    private val projectRepository: ProjectRepository,
    private val structuredChartRepository: StructuredChartRepository,
    private val authRepository: AuthRepository,
    private val createActivity: CreateActivityUseCase? = null,
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend operator fun invoke(patternId: String): UseCaseResult<ForkedProject> {
        val userId =
            authRepository.getCurrentUserId()
                ?: return UseCaseResult.Failure(UseCaseError.SignInRequired)

        val sourcePattern =
            patternRepository.getById(patternId)
                ?: return UseCaseResult.Failure(UseCaseError.ResourceNotFound)

        if (sourcePattern.visibility != Visibility.PUBLIC) {
            return UseCaseResult.Failure(UseCaseError.OperationNotAllowed)
        }

        val now = Clock.System.now()

        // Phase 36.3 (ADR-012 §1, §3): `parentPatternId = sourcePattern.id` is
        // the actual write of the 36.1 data spine. The 36.1 anchor comment
        // that previously sat above this block is closed.
        val forkedPattern =
            sourcePattern.copy(
                id = Uuid.random().toString(),
                ownerId = userId,
                visibility = Visibility.PRIVATE,
                parentPatternId = sourcePattern.id,
                createdAt = now,
                updatedAt = now,
            )

        return try {
            patternRepository.create(forkedPattern)

            // Phase 36.3 (ADR-012 §3, §7): best-effort chart clone. A chart-clone
            // failure does NOT roll back the already-created pattern + the
            // not-yet-created project — the user can re-fork later or work without
            // a chart. Surfaced via `ForkedProject.chartCloned` + `chartCloneError`
            // for the caller's UX layer.
            //
            // We use manual try/catch (not stdlib `runCatching`) because
            // `runCatching` catches `CancellationException` too — a job
            // cancellation during `forkFor` must propagate, otherwise the rest
            // of this use case keeps writing into a cancelled coroutine. See
            // the matching pattern in the outer try/catch below.
            val chartCloneResult: Result<Boolean> =
                try {
                    val cloned =
                        structuredChartRepository.forkFor(
                            sourcePatternId = sourcePattern.id,
                            newPatternId = forkedPattern.id,
                            newOwnerId = userId,
                        )
                    // `forkFor` returns null when the source has no chart — that is
                    // not a failure. Both branches of the `Boolean` map cleanly to
                    // the `chartCloned` field per ADR-012 §3.
                    Result.success(cloned != null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure(e)
                }

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

            UseCaseResult.Success(
                ForkedProject(
                    pattern = forkedPattern,
                    project = forkedProject,
                    chartCloned = chartCloneResult.getOrDefault(false),
                    // The inner try/catch above only catches `Exception`, so
                    // anything inside `chartCloneResult` is guaranteed to be
                    // an `Exception` — `toUseCaseError()` accepts the type
                    // unambiguously without a defensive `is Exception` guard.
                    chartCloneError = (chartCloneResult.exceptionOrNull() as Exception?)?.toUseCaseError(),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }
}
