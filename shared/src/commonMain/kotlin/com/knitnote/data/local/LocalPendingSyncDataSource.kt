package com.knitnote.data.local

import com.knitnote.data.sync.PendingSyncDataSource
import com.knitnote.data.sync.PendingSyncEntry
import com.knitnote.data.sync.SyncEntityType
import com.knitnote.data.sync.SyncOperation
import com.knitnote.data.sync.SyncStatus
import com.knitnote.db.KnitNoteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

class LocalPendingSyncDataSource(
    private val db: KnitNoteDatabase,
) : PendingSyncDataSource {

    private val queries get() = db.pendingSyncQueries

    override suspend fun enqueue(
        entityType: SyncEntityType,
        entityId: String,
        operation: SyncOperation,
        payload: String,
        createdAt: Long,
    ): Unit = withContext(Dispatchers.IO) {
        queries.insert(entityType.value, entityId, operation.value, payload, createdAt)
    }

    override suspend fun getAllPending(): List<PendingSyncEntry> = withContext(Dispatchers.IO) {
        queries.getAllPending().executeAsList().map { it.toDomain() }
    }

    override suspend fun getById(id: Long): PendingSyncEntry? = withContext(Dispatchers.IO) {
        queries.getById(id).executeAsOneOrNull()?.toDomain()
    }

    override suspend fun getByEntityId(entityId: String): List<PendingSyncEntry> = withContext(Dispatchers.IO) {
        queries.getByEntityId(entityId).executeAsList().map { it.toDomain() }
    }

    override suspend fun updatePayload(id: Long, payload: String): Unit = withContext(Dispatchers.IO) {
        queries.updatePayload(payload, id)
    }

    override suspend fun incrementRetry(id: Long): Unit = withContext(Dispatchers.IO) {
        queries.incrementRetry(id)
    }

    override suspend fun markFailed(id: Long): Unit = withContext(Dispatchers.IO) {
        queries.markFailed(id)
    }

    override suspend fun delete(id: Long): Unit = withContext(Dispatchers.IO) {
        queries.deleteById(id)
    }

    override suspend fun countPending(): Long = withContext(Dispatchers.IO) {
        queries.countPending().executeAsOne()
    }
}

private fun com.knitnote.db.PendingSyncEntity.toDomain(): PendingSyncEntry =
    PendingSyncEntry(
        id = id,
        entityType = SyncEntityType.fromValue(entity_type),
        entityId = entity_id,
        operation = SyncOperation.fromValue(operation),
        payload = payload,
        createdAt = created_at,
        retryCount = retry_count.toInt(),
        status = SyncStatus.fromValue(status),
    )
