package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.Chart
import io.github.b150005.skeinly.domain.repository.ChartRepository
import kotlinx.coroutines.flow.Flow

class ObserveChartUseCase(
    private val repository: ChartRepository,
) {
    operator fun invoke(patternId: String): Flow<Chart?> = repository.observeByPatternId(patternId)
}
