package com.knitnote.data.repository

import com.knitnote.data.local.LocalPatternDataSource
import com.knitnote.data.remote.RemotePatternDataSource
import com.knitnote.data.sync.SyncEntityType
import com.knitnote.data.sync.SyncManagerOperations
import com.knitnote.data.sync.SyncOperation
import com.knitnote.domain.model.Pattern
import com.knitnote.domain.model.Visibility
import com.knitnote.domain.repository.PatternRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class PatternRepositoryImpl(
    private val local: LocalPatternDataSource,
    private val remote: RemotePatternDataSource?,
    private val isOnline: StateFlow<Boolean>,
    private val syncManager: SyncManagerOperations,
    private val json: Json,
) : PatternRepository {
    override suspend fun getById(id: String): Pattern? {
        val localPattern = local.getById(id)
        if (localPattern != null || remote == null || !isOnline.value) return localPattern

        return try {
            remote.getById(id)?.also { local.upsert(it) }
        } catch (_: Exception) {
            localPattern
        }
    }

    override suspend fun getByOwnerId(ownerId: String): List<Pattern> {
        if (remote == null || !isOnline.value) return local.getByOwnerId(ownerId)

        return try {
            val remotePatterns = remote.getByOwnerId(ownerId)
            local.upsertAll(remotePatterns)
            remotePatterns
        } catch (_: Exception) {
            local.getByOwnerId(ownerId)
        }
    }

    override suspend fun getByVisibility(visibility: Visibility): List<Pattern> {
        if (visibility != Visibility.PUBLIC || remote == null || !isOnline.value) return emptyList()
        return try {
            remote.getPublic()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun observeById(id: String): Flow<Pattern?> = local.observeById(id)

    override fun observeByOwnerId(ownerId: String): Flow<List<Pattern>> = local.observeByOwnerId(ownerId)

    override suspend fun create(pattern: Pattern): Pattern {
        local.insert(pattern)
        syncManager.syncOrEnqueue(SyncEntityType.PATTERN, pattern.id, SyncOperation.INSERT, json.encodeToString(pattern))
        return pattern
    }

    override suspend fun update(pattern: Pattern): Pattern {
        local.update(pattern)
        syncManager.syncOrEnqueue(SyncEntityType.PATTERN, pattern.id, SyncOperation.UPDATE, json.encodeToString(pattern))
        return pattern
    }

    override suspend fun delete(id: String) {
        local.delete(id)
        syncManager.syncOrEnqueue(SyncEntityType.PATTERN, id, SyncOperation.DELETE, "")
    }
}
