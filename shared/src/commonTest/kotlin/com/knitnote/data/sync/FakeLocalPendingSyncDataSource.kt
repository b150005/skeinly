package com.knitnote.data.sync

/**
 * In-memory fake for [PendingSyncDataSource] used in SyncManager tests.
 */
class FakeLocalPendingSyncDataSource : PendingSyncDataSource {
    private var nextId = 1L
    private val entries = mutableListOf<PendingSyncEntry>()

    override suspend fun enqueue(
        entityType: String,
        entityId: String,
        operation: String,
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
                status = "pending",
            ),
        )
    }

    override suspend fun getAllPending(): List<PendingSyncEntry> =
        entries.filter { it.status == "pending" }.sortedBy { it.createdAt }

    override suspend fun getById(id: Long): PendingSyncEntry? =
        entries.find { it.id == id }

    override suspend fun getByEntityId(entityId: String): List<PendingSyncEntry> =
        entries.filter { it.entityId == entityId && it.status == "pending" }

    override suspend fun updatePayload(id: Long, payload: String) {
        val index = entries.indexOfFirst { it.id == id }
        if (index >= 0) entries[index] = entries[index].copy(payload = payload)
    }

    override suspend fun incrementRetry(id: Long) {
        val index = entries.indexOfFirst { it.id == id }
        if (index >= 0) entries[index] = entries[index].copy(retryCount = entries[index].retryCount + 1)
    }

    override suspend fun markFailed(id: Long) {
        val index = entries.indexOfFirst { it.id == id }
        if (index >= 0) entries[index] = entries[index].copy(status = "failed")
    }

    override suspend fun delete(id: Long) {
        entries.removeAll { it.id == id }
    }

    override suspend fun countPending(): Long =
        entries.count { it.status == "pending" }.toLong()

    /** Test helper: get all entries regardless of status */
    fun allEntries(): List<PendingSyncEntry> = entries.toList()
}
