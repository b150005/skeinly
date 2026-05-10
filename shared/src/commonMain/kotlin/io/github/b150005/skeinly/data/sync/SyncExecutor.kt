package io.github.b150005.skeinly.data.sync

import io.github.b150005.skeinly.domain.model.Chart
import io.github.b150005.skeinly.domain.model.ChartVariation
import io.github.b150005.skeinly.domain.model.ChartVersion
import io.github.b150005.skeinly.domain.model.Pattern
import io.github.b150005.skeinly.domain.model.Progress
import io.github.b150005.skeinly.domain.model.Project
import io.github.b150005.skeinly.domain.model.ProjectSegment
import io.github.b150005.skeinly.domain.model.Suggestion
import io.github.b150005.skeinly.domain.model.SuggestionComment
import kotlinx.serialization.json.Json

class SyncExecutor(
    private val remoteProject: RemoteProjectSyncOperations?,
    private val remoteProgress: RemoteProgressSyncOperations?,
    private val remotePattern: RemotePatternSyncOperations?,
    private val remoteChart: RemoteChartSyncOperations?,
    private val json: Json,
    private val remoteProjectSegment: RemoteProjectSegmentSyncOperations? = null,
    private val remoteChartVersion: RemoteChartVersionSyncOperations? = null,
    private val remoteChartVariation: RemoteChartVariationSyncOperations? = null,
    private val remoteSuggestion: RemoteSuggestionSyncOperations? = null,
    private val remoteSuggestionComment: RemoteSuggestionCommentSyncOperations? = null,
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
            SyncEntityType.STRUCTURED_CHART -> executeChart(entry)
            SyncEntityType.PROJECT_SEGMENT -> executeProjectSegment(entry)
            SyncEntityType.CHART_REVISION -> executeChartVersion(entry)
            SyncEntityType.CHART_BRANCH -> executeChartVariation(entry)
            SyncEntityType.PULL_REQUEST -> executeSuggestion(entry)
            SyncEntityType.PULL_REQUEST_COMMENT -> executeSuggestionComment(entry)
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

    private suspend fun executeChart(entry: PendingSyncEntry): Boolean {
        val remote = remoteChart ?: return true
        when (entry.operation) {
            SyncOperation.INSERT -> remote.upsert(json.decodeFromString<Chart>(entry.payload)) // idempotent create
            SyncOperation.UPDATE -> remote.update(json.decodeFromString<Chart>(entry.payload))
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

    private suspend fun executeChartVersion(entry: PendingSyncEntry): Boolean {
        val remote = remoteChartVersion ?: return true
        when (entry.operation) {
            // Append-only per ADR-013 §1. INSERT is the only write path; UPDATE
            // and DELETE are structurally forbidden (no RLS policy permits them
            // and SyncManager never enqueues them). UPDATE/DELETE branches are
            // silent no-ops with `return true` — matches `executeProgress`'s
            // unsupported-operation idiom. Throwing here would mark the entry
            // FAILED and trigger a retry storm on a row that can never succeed;
            // returning success consumes the impossible entry once and lets the
            // queue move on. If a non-INSERT ever lands here it indicates a bug
            // upstream (SyncManager enqueued an op that ChartVersionRepository
            // can never produce) — surfaceable via PendingSync log inspection.
            SyncOperation.INSERT -> remote.append(json.decodeFromString<ChartVersion>(entry.payload))
            SyncOperation.UPDATE,
            SyncOperation.DELETE,
            -> return true
        }
        return true
    }

    private suspend fun executeChartVariation(entry: PendingSyncEntry): Boolean {
        val remote = remoteChartVariation ?: return true
        when (entry.operation) {
            // INSERT and UPDATE both map to upsert: branch creation is idempotent
            // on (pattern_id, branch_name); tip movement is an idempotent re-write
            // of `tip_revision_id`. Phase 37.4 will start enqueuing UPDATE on
            // every save (tip advance) — already wired here.
            SyncOperation.INSERT -> remote.upsert(json.decodeFromString<ChartVariation>(entry.payload))
            SyncOperation.UPDATE -> remote.upsert(json.decodeFromString<ChartVariation>(entry.payload))
            SyncOperation.DELETE -> remote.delete(entry.entityId)
        }
        return true
    }

    private suspend fun executeSuggestion(entry: PendingSyncEntry): Boolean {
        val remote = remoteSuggestion ?: return true
        when (entry.operation) {
            // INSERT (open PR) and UPDATE (close PR) both map to upsert per
            // ADR-014 §7. Idempotent on `id`. Status → MERGED is NOT enqueued
            // here — it transitions via the merge_pull_request RPC and Realtime
            // echoes the merged row back through the local data source.
            SyncOperation.INSERT -> remote.upsert(json.decodeFromString<Suggestion>(entry.payload))
            SyncOperation.UPDATE -> remote.upsert(json.decodeFromString<Suggestion>(entry.payload))
            // DELETE is structurally forbidden (PRs are kept as audit trail).
            // SyncManager never enqueues this; defensive silent no-op same as
            // executeChartVersion's UPDATE/DELETE branches.
            SyncOperation.DELETE -> return true
        }
        return true
    }

    private suspend fun executeSuggestionComment(entry: PendingSyncEntry): Boolean {
        val remote = remoteSuggestionComment ?: return true
        when (entry.operation) {
            // Append-only per ADR-014 §1 — INSERT is the only write path.
            // UPDATE/DELETE are structurally forbidden (no RLS policy permits
            // them and SyncManager never enqueues them). Silent no-op same
            // pattern as executeChartVersion (Phase 37.1 precedent).
            SyncOperation.INSERT -> remote.appendComment(json.decodeFromString<SuggestionComment>(entry.payload))
            SyncOperation.UPDATE,
            SyncOperation.DELETE,
            -> return true
        }
        return true
    }
}
