package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.StructuredChart
import io.github.b150005.skeinly.domain.repository.StructuredChartRepository
import kotlinx.coroutines.flow.Flow

class ObserveStructuredChartUseCase(
    private val repository: StructuredChartRepository,
) {
    operator fun invoke(patternId: String): Flow<StructuredChart?> = repository.observeByPatternId(patternId)
}
