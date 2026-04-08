package com.knitnote.domain.repository

import com.knitnote.domain.model.Pattern
import com.knitnote.domain.model.Visibility
import kotlinx.coroutines.flow.Flow

interface PatternRepository {
    suspend fun getById(id: String): Pattern?
    suspend fun getByOwnerId(ownerId: String): List<Pattern>
    suspend fun getByVisibility(visibility: Visibility): List<Pattern>
    fun observeByOwnerId(ownerId: String): Flow<List<Pattern>>
    suspend fun create(pattern: Pattern): Pattern
    suspend fun update(pattern: Pattern): Pattern
    suspend fun delete(id: String)
}
