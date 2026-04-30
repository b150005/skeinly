package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.repository.ProjectSegmentRepository
import kotlinx.coroutines.CancellationException

/**
 * Clears every segment row for a project. The row-counter journal
 * (ProgressEntity notes + photos) is untouched per PRD AC-4.2.
 */
class ResetProjectProgressUseCase(
    private val repository: ProjectSegmentRepository,
) {
    suspend operator fun invoke(projectId: String): UseCaseResult<Unit> =
        try {
            repository.resetProject(projectId)
            UseCaseResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
