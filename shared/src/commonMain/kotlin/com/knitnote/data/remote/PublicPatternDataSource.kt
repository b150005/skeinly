package com.knitnote.data.remote

import com.knitnote.domain.model.Pattern

interface PublicPatternDataSource {
    suspend fun getPublic(
        searchQuery: String = "",
        limit: Int = 100,
    ): List<Pattern>
}
