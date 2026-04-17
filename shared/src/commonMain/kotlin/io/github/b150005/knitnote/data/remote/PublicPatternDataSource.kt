package io.github.b150005.knitnote.data.remote

import io.github.b150005.knitnote.domain.model.Pattern

interface PublicPatternDataSource {
    suspend fun getPublic(
        searchQuery: String = "",
        limit: Int = 100,
    ): List<Pattern>
}
