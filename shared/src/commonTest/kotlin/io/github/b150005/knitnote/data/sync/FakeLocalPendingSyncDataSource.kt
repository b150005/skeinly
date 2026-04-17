package io.github.b150005.knitnote.data.sync

/**
 * In-memory fake for [PendingSyncDataSource] used in SyncManager tests.
 */
class FakeLocalPendingSyncDataSource : PendingSyncDataSource {
    private var nextId = 1L
    private val entries = mutableListOf<PendingSyncEntry>()

    override suspend fun enqueue(
        entityType: SyncEntityType,
        entityId: String,
        operation: SyncOperation,
        payload: String,
        createdAt: Long,
    ) {
        entries.add(
            PendingSyncEntry(
                id = nextId++,
                entityType = entityType,
                entityId = entityId,
                operation = operation,
                payload = payload,
                createdAt = createdAt,
                retryCount = 0,
                status = SyncStatus.PENDING,
            ),
        )
    }

    override suspend fun getAllPending(): List<PendingSyncEntry> =
        entries.filter { it.status == SyncStatus.PENDING }.sortedBy { it.createdAt }

    override suspend fun getById(id: Long): PendingSyncEntry? = entries.find { it.id == id }

    override suspend fun getByEntityId(entityId: String): List<PendingSyncEntry> =
        entries.filter { it.entityId == entityId && it.status == SyncStatus.PENDING }

    override suspend fun updatePayload(
        id: Long,
        payload: String,
    ) {
        val index = entries.indexOfFirst { it.id == id }
        if (index >= 0) entries[index] = entries[index].copy(payload = payload)
    }

    override suspend fun incrementRetry(id: Long) {
        val index = entries.indexOfFirst { it.id == id }
        if (index >= 0) entries[index] = entries[index].copy(retryCount = entries[index].retryCount + 1)
    }

    override suspend fun markFailed(id: Long) {
        val index = entries.indexOfFirst { it.id == id }
        if (index >= 0) entries[index] = entries[index].copy(status = SyncStatus.FAILED)
    }

    override suspend fun delete(id: Long) {
        entries.removeAll { it.id == id }
    }

    override suspend fun countPending(): Long = entries.count { it.status == SyncStatus.PENDING }.toLong()

    /** Test helper: get all entries regardless of status */
    fun allEntries(): List<PendingSyncEntry> = entries.toList()
}
