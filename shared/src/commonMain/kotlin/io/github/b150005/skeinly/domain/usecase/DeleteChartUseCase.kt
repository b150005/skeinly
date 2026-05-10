package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.repository.StructuredChartRepository
import kotlin.coroutines.cancellation.CancellationException

class DeleteStructuredChartUseCase(
    private val repository: StructuredChartRepository,
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
