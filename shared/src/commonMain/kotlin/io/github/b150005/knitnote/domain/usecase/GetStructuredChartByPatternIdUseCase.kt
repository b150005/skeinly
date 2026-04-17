package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.repository.StructuredChartRepository
import kotlin.coroutines.cancellation.CancellationException

class GetStructuredChartByPatternIdUseCase(
    private val repository: StructuredChartRepository,
) {
    suspend operator fun invoke(patternId: String): UseCaseResult<StructuredChart?> =
        try {
            UseCaseResult.Success(repository.getByPatternId(patternId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
