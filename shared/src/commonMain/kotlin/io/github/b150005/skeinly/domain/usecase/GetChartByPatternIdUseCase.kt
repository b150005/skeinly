package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.Chart
import io.github.b150005.skeinly.domain.repository.ChartRepository
import kotlin.coroutines.cancellation.CancellationException

class GetChartByPatternIdUseCase(
    private val repository: ChartRepository,
) {
    suspend operator fun invoke(patternId: String): UseCaseResult<Chart?> =
        try {
            UseCaseResult.Success(repository.getByPatternId(patternId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
