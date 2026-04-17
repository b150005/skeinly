package io.github.b150005.knitnote.data.remote

import io.github.b150005.knitnote.domain.model.Pattern

class FakePublicPatternDataSource : PublicPatternDataSource {
    private val patterns = mutableListOf<Pattern>()
    var shouldFail = false

    override suspend fun getPublic(
        searchQuery: String,
        limit: Int,
    ): List<Pattern> {
        if (shouldFail) throw RuntimeException("Fake remote failure")
        return if (searchQuery.isBlank()) {
            patterns.take(limit)
        } else {
            patterns.filter { it.title.contains(searchQuery, ignoreCase = true) }.take(limit)
        }
    }

    fun addPattern(pattern: Pattern) {
        patterns.add(pattern)
    }
}
