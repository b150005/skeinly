package io.github.b150005.knitnote.domain.repository

import io.github.b150005.knitnote.domain.model.StructuredChart
import kotlinx.coroutines.flow.Flow

interface StructuredChartRepository {
    suspend fun getByPatternId(patternId: String): StructuredChart?

    fun observeByPatternId(patternId: String): Flow<StructuredChart?>

    suspend fun existsByPatternId(patternId: String): Boolean

    suspend fun create(chart: StructuredChart): StructuredChart

    suspend fun update(chart: StructuredChart): StructuredChart

    suspend fun delete(id: String)
}
