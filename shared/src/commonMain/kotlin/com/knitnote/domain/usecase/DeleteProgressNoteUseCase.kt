package com.knitnote.domain.usecase

import com.knitnote.domain.repository.ProgressRepository
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
