package io.github.b150005.knitnote.android.test

import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Visibility
import io.github.b150005.knitnote.domain.repository.PatternRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakePatternRepository : PatternRepository {
    private val patterns = MutableStateFlow<List<Pattern>>(emptyList())

    override suspend fun getById(id: String): Pattern? = patterns.value.find { it.id == id }

    override suspend fun getByOwnerId(ownerId: String): List<Pattern> = patterns.value.filter { it.ownerId == ownerId }

    override suspend fun getByVisibility(visibility: Visibility): List<Pattern> = patterns.value.filter { it.visibility == visibility }

    override fun observeByOwnerId(ownerId: String): Flow<List<Pattern>> = patterns.map { list -> list.filter { it.ownerId == ownerId } }

    override suspend fun create(pattern: Pattern): Pattern {
        patterns.value = patterns.value + pattern
        return pattern
    }

    override suspend fun update(pattern: Pattern): Pattern {
        patterns.value = patterns.value.map { if (it.id == pattern.id) pattern else it }
        return pattern
    }

    override suspend fun delete(id: String) {
        patterns.value = patterns.value.filter { it.id != id }
    }
}
