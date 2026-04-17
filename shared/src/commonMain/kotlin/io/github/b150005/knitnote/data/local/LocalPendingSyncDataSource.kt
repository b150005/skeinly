package io.github.b150005.knitnote.data.local

import io.github.b150005.knitnote.data.sync.PendingSyncDataSource
import io.github.b150005.knitnote.data.sync.PendingSyncEntry
import io.github.b150005.knitnote.data.sync.SyncEntityType
import io.github.b150005.knitnote.data.sync.SyncOperation
import io.github.b150005.knitnote.data.sync.SyncStatus
import io.github.b150005.knitnote.db.KnitNoteDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

class LocalPendingSyncDataSource(
    private val db: KnitNoteDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) : PendingSyncDataSource {
    private val queries get() = db.pendingSyncQueries

    override suspend fun enqueue(
        entityType: SyncEntityType,
        entityId: String,
        operation: SyncOperation,
        payload: String,
        createdAt: Long,
    ): Unit =
        withContext(ioDispatcher) {
            queries.insert(entityType.value, entityId, operation.value, payload, createdAt)
        }

    override suspend fun getAllPending(): List<PendingSyncEntry> =
        withContext(ioDispatcher) {
            queries.getAllPending().executeAsList().map { it.toDomain() }
        }

    override suspend fun getById(id: Long): PendingSyncEntry? =
        withContext(ioDispatcher) {
            queries.getById(id).executeAsOneOrNull()?.toDomain()
        }

    override suspend fun getByEntityId(entityId: String): List<PendingSyncEntry> =
        withContext(ioDispatcher) {
            queries.getByEntityId(entityId).executeAsList().map { it.toDomain() }
        }

    override suspend fun updatePayload(
        id: Long,
        payload: String,
    ): Unit =
        withContext(ioDispatcher) {
            queries.updatePayload(payload, id)
        }

    override suspend fun incrementRetry(id: Long): Unit =
        withContext(ioDispatcher) {
            queries.incrementRetry(id)
        }

    override suspend fun markFailed(id: Long): Unit =
        withContext(ioDispatcher) {
            queries.markFailed(id)
        }

    override suspend fun delete(id: Long): Unit =
        withContext(ioDispatcher) {
            queries.deleteById(id)
        }

    override suspend fun countPending(): Long =
        withContext(ioDispatcher) {
            queries.countPending().executeAsOne()
        }
}

private fun io.github.b150005.knitnote.db.PendingSyncEntity.toDomain(): PendingSyncEntry =
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
