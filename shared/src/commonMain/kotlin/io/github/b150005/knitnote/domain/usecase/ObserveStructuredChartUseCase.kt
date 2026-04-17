package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.repository.StructuredChartRepository
import kotlinx.coroutines.flow.Flow

class ObserveStructuredChartUseCase(
    private val repository: StructuredChartRepository,
) {
    operator fun invoke(patternId: String): Flow<StructuredChart?> = repository.observeByPatternId(patternId)
}
