package io.github.b150005.knitnote.data.remote

import io.github.b150005.knitnote.domain.model.Pattern

class FakePublicPatternDataSource : PublicPatternDataSource {
    private val patterns = mutableListOf<Pattern>()
    private val patternsWithCharts = mutableSetOf<String>()
    var shouldFail = false

    override suspend fun getPublic(
        searchQuery: String,
        limit: Int,
        chartsOnly: Boolean,
    ): PublicPatternsResult {
        if (shouldFail) throw RuntimeException("Fake remote failure")
        val matching =
            if (searchQuery.isBlank()) {
                patterns
            } else {
                patterns.filter { it.title.contains(searchQuery, ignoreCase = true) }
            }
        val filtered =
            if (chartsOnly) {
                matching.filter { it.id in patternsWithCharts }
            } else {
                matching
            }
        val limited = filtered.take(limit)
        // Companion set is always populated regardless of `chartsOnly`. When
        // `chartsOnly = true` it equals the returned id set; when false it
        // names which of the returned ids have charts (Phase 36.4 / ADR-012 §5).
        val companion = limited.map { it.id }.toSet().intersect(patternsWithCharts)
        return PublicPatternsResult(patterns = limited, patternsWithCharts = companion)
    }

    fun addPattern(pattern: Pattern) {
        patterns.add(pattern)
    }

    /**
     * Mark the given pattern id as having a structured chart attached. Used by
     * Phase 36.4 tests to exercise the `chartsOnly` filter and the
     * `patternsWithCharts` companion set.
     */
    fun markHasChart(patternId: String) {
        patternsWithCharts.add(patternId)
    }
}
