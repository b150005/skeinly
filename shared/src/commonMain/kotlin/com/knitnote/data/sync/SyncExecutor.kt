package com.knitnote.data.sync

import com.knitnote.domain.model.Pattern
import com.knitnote.domain.model.Progress
import com.knitnote.domain.model.Project
import kotlinx.serialization.json.Json

class SyncExecutor(
    private val remoteProject: RemoteProjectSyncOperations?,
    private val remoteProgress: RemoteProgressSyncOperations?,
    private val remotePattern: RemotePatternSyncOperations?,
    private val json: Json,
) {
    /**
     * Execute a pending sync entry against the remote data source.
     * Returns true on success. Throws on remote failure (caller handles retry).
     * Throws [kotlinx.serialization.SerializationException] for corrupt payloads.
     */
    suspend fun execute(entry: PendingSyncEntry): Boolean =
        when (entry.entityType) {
            SyncEntityType.PROJECT -> executeProject(entry)
            SyncEntityType.PROGRESS -> executeProgress(entry)
            SyncEntityType.PATTERN -> executePattern(entry)
        }

    private suspend fun executeProject(entry: PendingSyncEntry): Boolean {
        val remote = remoteProject ?: return true
        when (entry.operation) {
            SyncOperation.INSERT -> remote.upsert(json.decodeFromString<Project>(entry.payload)) // idempotent create: safe to retry
            SyncOperation.UPDATE -> remote.update(json.decodeFromString<Project>(entry.payload))
            SyncOperation.DELETE -> remote.delete(entry.entityId)
        }
        return true
    }

    private suspend fun executeProgress(entry: PendingSyncEntry): Boolean {
        val remote = remoteProgress ?: return true
        when (entry.operation) {
            SyncOperation.INSERT -> remote.upsert(json.decodeFromString<Progress>(entry.payload)) // idempotent create: safe to retry
            SyncOperation.DELETE -> remote.delete(entry.entityId)
            SyncOperation.UPDATE -> return true // no-op: progress update not yet supported; no code path enqueues this
        }
        return true
    }

    private suspend fun executePattern(entry: PendingSyncEntry): Boolean {
        val remote = remotePattern ?: return true
        when (entry.operation) {
            SyncOperation.INSERT -> remote.upsert(json.decodeFromString<Pattern>(entry.payload)) // idempotent create: safe to retry
            SyncOperation.UPDATE -> remote.update(json.decodeFromString<Pattern>(entry.payload))
            SyncOperation.DELETE -> remote.delete(entry.entityId)
        }
        return true
    }
}
