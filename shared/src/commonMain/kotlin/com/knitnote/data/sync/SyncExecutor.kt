package com.knitnote.data.sync

import com.knitnote.domain.model.Progress
import com.knitnote.domain.model.Project
import kotlinx.serialization.json.Json

class SyncExecutor(
    private val remoteProject: RemoteProjectSyncOperations?,
    private val remoteProgress: RemoteProgressSyncOperations?,
    private val json: Json,
) {

    /**
     * Execute a pending sync entry against the remote data source.
     * Returns true on success. Throws on remote failure (caller handles retry).
     * Throws [kotlinx.serialization.SerializationException] for corrupt payloads.
     */
    suspend fun execute(entry: PendingSyncEntry): Boolean =
        when (entry.entityType) {
            "project" -> executeProject(entry)
            "progress" -> executeProgress(entry)
            else -> true // unknown type, skip
        }

    private suspend fun executeProject(entry: PendingSyncEntry): Boolean {
        val remote = remoteProject ?: return true
        when (entry.operation) {
            "insert" -> remote.insert(json.decodeFromString<Project>(entry.payload))
            "update" -> remote.update(json.decodeFromString<Project>(entry.payload))
            "delete" -> remote.delete(entry.entityId)
            else -> return true
        }
        return true
    }

    private suspend fun executeProgress(entry: PendingSyncEntry): Boolean {
        val remote = remoteProgress ?: return true
        when (entry.operation) {
            "insert" -> remote.insert(json.decodeFromString<Progress>(entry.payload))
            "delete" -> remote.delete(entry.entityId)
            else -> return true
        }
        return true
    }
}
