package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.repository.ProjectRepository
import kotlin.coroutines.cancellation.CancellationException

class DeleteProjectUseCase(
    private val repository: ProjectRepository,
) {
    suspend operator fun invoke(id: String): UseCaseResult<Unit> =
        try {
            repository.delete(id)
            UseCaseResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
