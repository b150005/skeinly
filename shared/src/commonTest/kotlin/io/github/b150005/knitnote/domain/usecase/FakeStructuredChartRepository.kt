package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.repository.StructuredChartRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeStructuredChartRepository : StructuredChartRepository {
    private val charts = MutableStateFlow<List<StructuredChart>>(emptyList())

    var failNext: Throwable? = null

    override suspend fun getByPatternId(patternId: String): StructuredChart? {
        failNext?.let { throw it.also { failNext = null } }
        return charts.value.firstOrNull { it.patternId == patternId }
    }

    override fun observeByPatternId(patternId: String): Flow<StructuredChart?> =
        charts.map { list -> list.firstOrNull { it.patternId == patternId } }

    override suspend fun existsByPatternId(patternId: String): Boolean = charts.value.any { it.patternId == patternId }

    override suspend fun create(chart: StructuredChart): StructuredChart {
        failNext?.let { throw it.also { failNext = null } }
        charts.value = charts.value + chart
        return chart
    }

    override suspend fun update(chart: StructuredChart): StructuredChart {
        failNext?.let { throw it.also { failNext = null } }
        charts.value = charts.value.map { if (it.id == chart.id) chart else it }
        return chart
    }

    override suspend fun delete(id: String) {
        failNext?.let { throw it.also { failNext = null } }
        charts.value = charts.value.filter { it.id != id }
    }

    fun seed(chart: StructuredChart) {
        charts.value = charts.value + chart
    }
}
