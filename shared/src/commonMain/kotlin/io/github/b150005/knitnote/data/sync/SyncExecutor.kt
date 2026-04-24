package io.github.b150005.knitnote.data.sync

import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Progress
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectSegment
import io.github.b150005.knitnote.domain.model.StructuredChart
import kotlinx.serialization.json.Json

class SyncExecutor(
    private val remoteProject: RemoteProjectSyncOperations?,
    private val remoteProgress: RemoteProgressSyncOperations?,
    private val remotePattern: RemotePatternSyncOperations?,
    private val remoteStructuredChart: RemoteStructuredChartSyncOperations?,
    private val json: Json,
    private val remoteProjectSegment: RemoteProjectSegmentSyncOperations? = null,
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
            SyncEntityType.STRUCTURED_CHART -> executeStructuredChart(entry)
            SyncEntityType.PROJECT_SEGMENT -> executeProjectSegment(entry)
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

    private suspend fun executeStructuredChart(entry: PendingSyncEntry): Boolean {
        val remote = remoteStructuredChart ?: return true
        when (entry.operation) {
            SyncOperation.INSERT -> remote.upsert(json.decodeFromString<StructuredChart>(entry.payload)) // idempotent create
            SyncOperation.UPDATE -> remote.update(json.decodeFromString<StructuredChart>(entry.payload))
            SyncOperation.DELETE -> remote.delete(entry.entityId)
        }
        return true
    }

    private suspend fun executeProjectSegment(entry: PendingSyncEntry): Boolean {
        val remote = remoteProjectSegment ?: return true
        when (entry.operation) {
            // wip/done transitions both map to UPSERT; reset maps to DELETE. No distinct UPDATE.
            SyncOperation.INSERT -> remote.upsert(json.decodeFromString<ProjectSegment>(entry.payload))
            SyncOperation.UPDATE -> remote.upsert(json.decodeFromString<ProjectSegment>(entry.payload))
            SyncOperation.DELETE -> remote.delete(entry.entityId)
        }
        return true
    }
}
