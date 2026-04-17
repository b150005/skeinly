package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.repository.ProgressRepository
import kotlin.coroutines.cancellation.CancellationException

class DeleteProgressNoteUseCase(
    private val repository: ProgressRepository,
) {
    suspend operator fun invoke(progressId: String): UseCaseResult<Unit> =
        try {
            repository.delete(progressId)
            UseCaseResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
