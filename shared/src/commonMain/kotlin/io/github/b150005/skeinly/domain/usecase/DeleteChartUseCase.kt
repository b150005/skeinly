package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.repository.ChartRepository
import kotlin.coroutines.cancellation.CancellationException

class DeleteChartUseCase(
    private val repository: ChartRepository,
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
