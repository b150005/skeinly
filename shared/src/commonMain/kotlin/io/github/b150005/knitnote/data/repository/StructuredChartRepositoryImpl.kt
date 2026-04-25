package io.github.b150005.knitnote.data.repository

import io.github.b150005.knitnote.data.local.LocalChartBranchDataSource
import io.github.b150005.knitnote.data.local.LocalStructuredChartDataSource
import io.github.b150005.knitnote.data.remote.RemoteStructuredChartDataSource
import io.github.b150005.knitnote.data.sync.SyncEntityType
import io.github.b150005.knitnote.data.sync.SyncManagerOperations
import io.github.b150005.knitnote.data.sync.SyncOperation
import io.github.b150005.knitnote.domain.model.ChartBranch
import io.github.b150005.knitnote.domain.model.ChartRevision
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.model.toStructuredChart
import io.github.b150005.knitnote.domain.repository.ChartBranchRepository
import io.github.b150005.knitnote.domain.repository.ChartRevisionRepository
import io.github.b150005.knitnote.domain.repository.StructuredChartRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
class StructuredChartRepositoryImpl(
    private val local: LocalStructuredChartDataSource,
    private val remote: RemoteStructuredChartDataSource?,
    private val isOnline: StateFlow<Boolean>,
    private val syncManager: SyncManagerOperations,
    private val json: Json,
    // Phase 37.1 (ADR-013 §1, §7): commit history + branch model.
    // Optional with `null` defaults so existing test call-sites that construct
    // this repo directly without seeding a revision/branch layer continue to
    // compile. Production wiring (RepositoryModule) always passes non-null.
    private val chartRevisionRepository: ChartRevisionRepository? = null,
    private val localChartBranch: LocalChartBranchDataSource? = null,
    // Phase 37.4 (ADR-013 §7): advance the current branch's tip on every save.
    // Optional with `null` default for the same reason the 37.1 deps are
    // optional — existing test call-sites that bypass the branch layer stay
    // green. Production wiring (RepositoryModule) always passes non-null.
    private val chartBranchRepository: ChartBranchRepository? = null,
) : StructuredChartRepository {
    /**
     * Serializes the read-then-write triple in [update] and [forkFor] so two
     * concurrent saves on the same chart cannot both read the same prior tip
     * and produce a branching commit chain (ADR-013 §1: history is linear).
     * SQLite is single-writer at the driver layer, but the read happens before
     * the write inside the repository — an in-process race would still observe
     * the pre-update tip on both callers. This mutex is the in-process guard.
     */
    private val writeMutex = Mutex()

    override suspend fun getByPatternId(patternId: String): StructuredChart? {
        val localChart = local.getByPatternId(patternId)
        if (localChart != null || remote == null || !isOnline.value) return localChart

        return try {
            remote.getByPatternId(patternId)?.also { local.upsert(it) }
        } catch (_: Exception) {
            localChart
        }
    }

    override fun observeByPatternId(patternId: String): Flow<StructuredChart?> = local.observeByPatternId(patternId)

    override suspend fun existsByPatternId(patternId: String): Boolean = local.existsByPatternId(patternId)

    override suspend fun create(chart: StructuredChart): StructuredChart =
        writeMutex.withLock {
            local.insert(chart)
            syncManager.syncOrEnqueue(
                SyncEntityType.STRUCTURED_CHART,
                chart.id,
                SyncOperation.INSERT,
                json.encodeToString(chart),
            )
            // ADR-013 §7: append the initial revision row alongside the tip and
            // bootstrap the default "main" branch on first save. Append before
            // ensureDefaultBranch so the branch's tip_revision_id has a row to
            // point at server-side (the FK is `ON DELETE RESTRICT`).
            appendRevisionFromTip(chart, commitMessage = null, parentRevisionId = null)
            ensureDefaultBranch(chart.patternId, chart.ownerId, chart.revisionId)
            chart
        }

    override suspend fun update(chart: StructuredChart): StructuredChart =
        writeMutex.withLock {
            // ADR-013 §1: append the new revision BEFORE the tip update. The local
            // tip update happens immediately; both writes flow through PendingSync
            // independently and the network may interleave them on the wire, but
            // ordering at the local DB layer is deterministic. The mutex ensures
            // two concurrent saves on the same chart cannot both read the same
            // pre-update tip and produce a branching parent chain.
            val previousChart = local.getByPatternId(chart.patternId)
            appendRevisionFromTip(
                chart,
                commitMessage = null,
                parentRevisionId = previousChart?.revisionId ?: chart.parentRevisionId,
            )
            local.update(chart)
            syncManager.syncOrEnqueue(
                SyncEntityType.STRUCTURED_CHART,
                chart.id,
                SyncOperation.UPDATE,
                json.encodeToString(chart),
            )
            // ADR-013 §7: advance the current branch's tip pointer to the new
            // revision. "Current branch" = any branch whose `tip_revision_id`
            // matched the prior tip — defines co-located branches as advancing
            // together (e.g. immediately after `createBranch` from the same tip).
            // If no branch row matches, fall back to advancing "main" so initial
            // bootstrap and pre-37.4 chart_documents stay coherent.
            advanceCurrentBranchTip(
                patternId = chart.patternId,
                previousRevisionId = previousChart?.revisionId,
                newRevisionId = chart.revisionId,
            )
            chart
        }

    override suspend fun delete(id: String) {
        local.delete(id)
        syncManager.syncOrEnqueue(SyncEntityType.STRUCTURED_CHART, id, SyncOperation.DELETE, "")
    }

    override suspend fun forkFor(
        sourcePatternId: String,
        newPatternId: String,
        newOwnerId: String,
    ): StructuredChart? {
        // getByPatternId() resolves local-first then falls back to remote when online,
        // so a forker who has not yet visited the source pattern still hits a fresh
        // copy. Returns null when the source has no chart at all — caller (ADR-012 §3)
        // treats this as "nothing to clone" and proceeds with a metadata-only fork.
        val source = getByPatternId(sourcePatternId) ?: return null
        return writeMutex.withLock {
            val now = Clock.System.now()
            val cloned =
                source.copy(
                    id = Uuid.random().toString(),
                    patternId = newPatternId,
                    ownerId = newOwnerId,
                    revisionId = Uuid.random().toString(),
                    // ADR-012 §2: commit-rooted lineage. The fork's first revision points
                    // back at the source's revision so Phase 37 collaboration can walk
                    // ancestry without retroactive inference.
                    parentRevisionId = source.revisionId,
                    // contentHash carried verbatim — drawing identity is unchanged on a
                    // fork per ADR-008 §7 (`content_hash` describes drawable content,
                    // not ownership/lineage).
                    createdAt = now,
                    updatedAt = now,
                )
            local.insert(cloned)
            syncManager.syncOrEnqueue(
                SyncEntityType.STRUCTURED_CHART,
                cloned.id,
                SyncOperation.INSERT,
                json.encodeToString(cloned),
            )
            // ADR-013 §7: forks immediately have a 1-revision history and a usable
            // 'main' branch from the first load. Without ensureDefaultBranch the
            // 37.4 branch picker would surface no branches on forked patterns.
            appendRevisionFromTip(cloned, commitMessage = null, parentRevisionId = source.revisionId)
            ensureDefaultBranch(cloned.patternId, cloned.ownerId, cloned.revisionId)
            cloned
        }
    }

    /**
     * Append a [ChartRevision] mirroring the tip's drawing payload + lineage.
     *
     * `parentRevisionId` is taken from the chart's prior tip on update paths
     * (caller passes `previousChart.revisionId`), and from the chart's own
     * `parentRevisionId` on create / fork paths.
     *
     * `createdAt` uses wall-clock time at append rather than `chart.updatedAt`
     * to keep the `getHistoryForPattern` ORDER BY `created_at DESC` ordering
     * deterministic — a caller who constructs a chart pre-network-delay could
     * hand us a stale `updatedAt` that ties or out-of-orders the history.
     *
     * No-ops if the [ChartRevisionRepository] dependency is absent (test
     * call-sites; production always provides it).
     */
    private suspend fun appendRevisionFromTip(
        chart: StructuredChart,
        commitMessage: String?,
        parentRevisionId: String?,
    ) {
        val repo = chartRevisionRepository ?: return
        val revision =
            ChartRevision(
                id = Uuid.random().toString(),
                patternId = chart.patternId,
                ownerId = chart.ownerId,
                authorId = chart.ownerId,
                schemaVersion = chart.schemaVersion,
                storageVariant = chart.storageVariant,
                coordinateSystem = chart.coordinateSystem,
                extents = chart.extents,
                layers = chart.layers,
                revisionId = chart.revisionId,
                parentRevisionId = parentRevisionId,
                contentHash = chart.contentHash,
                commitMessage = commitMessage,
                createdAt = Clock.System.now(),
                craftType = chart.craftType,
                readingConvention = chart.readingConvention,
            )
        repo.append(revision)
    }

    /**
     * Get-or-create the default "main" branch for a pattern. Skips both the
     * local upsert AND the remote sync enqueue when a "main" row already
     * exists locally — racing devices on the same fork would otherwise
     * generate distinct UUIDs that race the server-side
     * `onConflict = "pattern_id,branch_name"` clause and silently overwrite
     * each other's `id`. Resolving to `getByPatternIdAndName` first means at
     * most one CHART_BRANCH INSERT is enqueued per pattern.
     */
    private suspend fun ensureDefaultBranch(
        patternId: String,
        ownerId: String,
        initialRevisionId: String,
    ) {
        val branchDs = localChartBranch ?: return
        if (branchDs.getByPatternIdAndName(patternId, ChartBranch.DEFAULT_BRANCH_NAME) != null) {
            // Branch already exists for this pattern — nothing to enqueue.
            return
        }
        val now = Clock.System.now()
        val branch =
            ChartBranch(
                id = Uuid.random().toString(),
                patternId = patternId,
                ownerId = ownerId,
                branchName = ChartBranch.DEFAULT_BRANCH_NAME,
                tipRevisionId = initialRevisionId,
                createdAt = now,
                updatedAt = now,
            )
        branchDs.upsert(branch)
        syncManager.syncOrEnqueue(
            SyncEntityType.CHART_BRANCH,
            branch.id,
            SyncOperation.INSERT,
            json.encodeToString(branch),
        )
    }

    /**
     * Phase 37.4 (ADR-013 §7): rewrite the tip pointer for a branch switch
     * WITHOUT appending history. The revision rows already exist (we are
     * reading from one); switching branches is pointer movement.
     *
     * The mutex serializes the read of the current tip pointer row and the
     * write that rewrites it from [targetRevision]'s payload. This makes a
     * concurrent `update()` racing the switch a no-op for the switch's
     * payload — either the update lands first (its revision becomes the new
     * tip; the switch then re-reads and rewrites) or the switch lands first
     * (its payload is the new tip; the update appends on top of it). Without
     * the read-write atomicity here, a use-case-level read+write would
     * silently overwrite an in-flight save.
     */
    override suspend fun setTip(
        patternId: String,
        targetRevision: ChartRevision,
    ): StructuredChart? =
        writeMutex.withLock {
            val current = local.getByPatternId(patternId) ?: return@withLock null
            val rebuilt =
                targetRevision.toStructuredChart().copy(
                    id = current.id,
                    createdAt = current.createdAt,
                    updatedAt = Clock.System.now(),
                )
            local.update(rebuilt)
            syncManager.syncOrEnqueue(
                SyncEntityType.STRUCTURED_CHART,
                rebuilt.id,
                SyncOperation.UPDATE,
                json.encodeToString(rebuilt),
            )
            rebuilt
        }

    /**
     * Advance the tip of every branch whose `tip_revision_id` matches the
     * prior chart's revision (typically just one — the user's current branch).
     *
     * Why "every" rather than "the": after a `createBranch` from the same tip,
     * two branches are co-located on the same revision until the next save.
     * Advancing only one would leave the other dangling at the prior tip,
     * silently breaking the "I just branched from here" mental model.
     *
     * Fallback: when no branch row matches (defensive: pre-37.4 charts that
     * predate `ensureDefaultBranch` wiring, or test setups that skip the
     * branch layer entirely), advance "main" via `getByPatternIdAndName` and
     * silently no-op if "main" itself is absent.
     */
    private suspend fun advanceCurrentBranchTip(
        patternId: String,
        previousRevisionId: String?,
        newRevisionId: String,
    ) {
        val branchRepo = chartBranchRepository ?: return
        if (previousRevisionId == null) {
            // First save into a chart that predates branch wiring (or test
            // shim). Try advancing "main" if it exists; ensureDefaultBranch
            // already set the tip on first create so this is mostly defensive.
            branchRepo.advanceTip(patternId, ChartBranch.DEFAULT_BRANCH_NAME, newRevisionId)
            return
        }
        val branches = branchRepo.getByPatternId(patternId)
        val matching = branches.filter { it.tipRevisionId == previousRevisionId }
        if (matching.isEmpty()) {
            // Defensive fall-through: no branch points at the prior tip. This
            // shouldn't happen post-37.4 but guards against pre-37.4 data
            // where chart_documents.revision_id evolved without any branch
            // row tracking it.
            branchRepo.advanceTip(patternId, ChartBranch.DEFAULT_BRANCH_NAME, newRevisionId)
            return
        }
        matching.forEach { branch ->
            branchRepo.advanceTip(patternId, branch.branchName, newRevisionId)
        }
    }
}
