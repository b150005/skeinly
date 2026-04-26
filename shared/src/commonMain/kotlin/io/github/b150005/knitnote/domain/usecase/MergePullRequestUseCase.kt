package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.data.mapper.toDocumentJson
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.PullRequest
import io.github.b150005.knitnote.domain.model.PullRequestStatus
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.PatternRepository
import io.github.b150005.knitnote.domain.repository.PullRequestMergeOperations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Phase 38.4 (ADR-014 §5, §6, §8) — invoke the SECURITY DEFINER
 * `merge_pull_request` RPC.
 *
 * The RPC is the only writer permitted to produce
 * `chart_revisions.author_id != owner_id` rows. This use case bypasses the
 * standard local-then-sync orchestration (the caller cannot pre-write a stub
 * locally and reconcile, since the local SQLDelight INSERT would not satisfy
 * the multi-author RLS WITH CHECK that the server function bypasses).
 *
 * Caller path: target owner taps "Merge" → `ConflictDetector.detect(...)` →
 * if `report.isClean`, the resolved document is straight from the source tip
 * (auto-applied) → if not, the resolver UI builds the resolved document by
 * applying the user's [io.github.b150005.knitnote.domain.chart.ConflictDetector]
 * picks per cell over `mine` → this use case mints a fresh revision id and
 * invokes the RPC.
 *
 * **Validation envelope.** RPC errors (PR not open, source tip drifted,
 * caller is not target owner, target branch missing tip) surface as Postgres
 * exceptions with descriptive messages. They are categorised into:
 * - "Caller is not target owner" / "PR not open" → [UseCaseError.Validation]
 * - "Source tip drifted; re-resolve required" → [UseCaseError.Validation]
 * - Generic / network → [Exception.toUseCaseError]
 *
 * The use case re-validates the OPEN-status precondition client-side too —
 * a stale UI state showing an already-merged PR should not even attempt the
 * round trip.
 *
 * **Atomicity guarantee.** A successful RPC return implies all 4 server-side
 * mutations committed (revision INSERT, branch tip UPDATE, chart_documents
 * UPDATE, PR row UPDATE). Realtime then echoes the merged PR + advanced tip
 * back through the existing `pull-requests-incoming-<ownerId>` and
 * `chart-revisions-<ownerId>` channels — local cache rehydrates without
 * additional code paths in this use case.
 */
@OptIn(ExperimentalUuidApi::class)
class MergePullRequestUseCase(
    private val mergeOperations: PullRequestMergeOperations?,
    private val patternRepository: PatternRepository,
    private val authRepository: AuthRepository,
    private val json: Json,
) {
    /**
     * Invoke with [resolvedChart]'s drawing payload (extents + layers + craft
     * metadata) as the merge result. The chart's row-level fields (id, owner,
     * etc.) are NOT used — the RPC writes its own envelope. Only the document
     * payload + content hash matter for the merge result.
     *
     * [strategy] is currently always [MergeStrategy.SQUASH] in v1 (the
     * fast-forward path is a server-side enum value reserved for forward
     * compatibility but no client path exercises it; ADR-014 §5 final
     * paragraph).
     */
    suspend operator fun invoke(
        pullRequest: PullRequest,
        resolvedChart: StructuredChart,
        strategy: MergeStrategy = MergeStrategy.SQUASH,
    ): UseCaseResult<MergeResult> {
        // Client-side preconditions before the round trip. The RPC re-validates
        // server-side; these reject stale UI state without paying the network
        // cost.
        if (pullRequest.status != PullRequestStatus.OPEN) {
            return UseCaseResult.Failure(
                UseCaseError.Validation("Only open pull requests can be merged"),
            )
        }
        val callerId =
            authRepository.getCurrentUserId()
                ?: return UseCaseResult.Failure(
                    UseCaseError.Authentication(IllegalStateException("Must be signed in to merge")),
                )
        // Defense-in-depth: surface the "not target owner" branch with a
        // clear message client-side rather than letting the RPC's RAISE
        // EXCEPTION bubble up as a generic Unknown. The RPC's
        // `WHERE owner_id = v_caller` clause is the actual security boundary;
        // this is just UX.
        val targetPattern =
            try {
                patternRepository.getById(pullRequest.targetPatternId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
        if (targetPattern == null || targetPattern.ownerId != callerId) {
            return UseCaseResult.Failure(
                UseCaseError.Validation("Only the target owner can merge this pull request"),
            )
        }

        val merge =
            mergeOperations ?: return UseCaseResult.Failure(
                UseCaseError.Validation("Merge is unavailable in offline-only mode"),
            )

        val newRevisionId = Uuid.random().toString()
        val mergedDocument: JsonElement =
            json.parseToJsonElement(resolvedChart.toDocumentJson(json))
        val mergedContentHash =
            StructuredChart.computeContentHash(
                extents = resolvedChart.extents,
                layers = resolvedChart.layers,
                json = json,
            )

        return try {
            val returnedRevisionId =
                merge.merge(
                    pullRequestId = pullRequest.id,
                    strategy = strategy.wireValue,
                    mergedDocument = mergedDocument,
                    mergedContentHash = mergedContentHash,
                    resolvedRevisionId = newRevisionId,
                )
            UseCaseResult.Success(
                MergeResult(
                    pullRequestId = pullRequest.id,
                    mergedRevisionId = returnedRevisionId,
                    mergedContentHash = mergedContentHash,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(mapMergeError(e))
        }
    }

    /**
     * Map specific RPC RAISE EXCEPTION messages onto distinct
     * [UseCaseError.Validation] surfaces so the UI can phrase the error
     * appropriately. Anything not recognised falls back to the generic
     * [Exception.toUseCaseError] mapping.
     */
    private fun mapMergeError(e: Exception): UseCaseError {
        val msg = e.message.orEmpty()
        return when {
            msg.contains("Source tip drifted", ignoreCase = true) ->
                UseCaseError.Validation("Source tip drifted; please re-resolve and try again")
            msg.contains("PR not open", ignoreCase = true) ->
                UseCaseError.Validation("This pull request is no longer open")
            msg.contains("Caller is not target owner", ignoreCase = true) ->
                UseCaseError.Authentication(e)
            msg.contains("PR not found", ignoreCase = true) ->
                UseCaseError.NotFound("Pull request not found")
            msg.contains("Target branch has no tip", ignoreCase = true) ->
                UseCaseError.Validation("Target branch is missing its tip; cannot merge")
            else -> e.toUseCaseError()
        }
    }
}

/**
 * Merge strategy. Phase 38 v1 always uses [SQUASH]; [FAST_FORWARD] is
 * reserved on the SQL side per ADR-014 §5 but no client path exercises it.
 */
enum class MergeStrategy(
    val wireValue: String,
) {
    SQUASH("squash"),
    FAST_FORWARD("fast_forward"),
}

/**
 * Outcome of a successful merge. The returned [mergedRevisionId] matches the
 * id the use case minted — the RPC echoes it back as confirmation.
 */
data class MergeResult(
    val pullRequestId: String,
    val mergedRevisionId: String,
    val mergedContentHash: String,
)

/**
 * Apply a [io.github.b150005.knitnote.domain.chart.ConflictReport]'s resolution
 * map over [mine] (the target tip) to produce the merged chart's drawing payload.
 *
 * Phase 38.4 default: callers start from `mine` (target tip), then apply
 * `autoFromTheirs` on top, then per-conflict picks. This helper covers the
 * mechanical part — `mine` is preserved by default, `autoFromTheirs` lands
 * regardless, and the [conflictPicks] map decides the contested cells.
 *
 * Returns a fresh [StructuredChart] with the resolved cells; row-level fields
 * (id, owner, revisionId, etc.) are inherited from [mine] so the RPC sees a
 * structurally-valid envelope (the RPC ignores them — only the document
 * payload + content hash matter).
 */
fun applyResolutions(
    mine: StructuredChart,
    autoFromTheirs: List<io.github.b150005.knitnote.domain.model.CellChange>,
    conflictPicks: Map<io.github.b150005.knitnote.domain.chart.CellCoordinate, ConflictResolution>,
    autoLayerFromTheirs: List<io.github.b150005.knitnote.domain.model.LayerChange>,
    layerConflictPicks: Map<String, ConflictResolution>,
    theirs: StructuredChart,
    ancestor: StructuredChart,
): StructuredChart {
    val layersById: MutableMap<String, ChartLayer> =
        mine.layers.associateBy { it.id }.toMutableMap()

    // Layer-level edits land first so subsequent cell-level picks see the
    // correct layer set — a layer added by theirs needs to exist before any
    // cell change references it, and a layer removed by theirs evaporates
    // any cells we'd otherwise have inherited from mine.
    for (change in autoLayerFromTheirs) {
        when (change) {
            is io.github.b150005.knitnote.domain.model.LayerChange.Added ->
                layersById[change.layer.id] = change.layer
            is io.github.b150005.knitnote.domain.model.LayerChange.Removed ->
                layersById.remove(change.layerId)
            is io.github.b150005.knitnote.domain.model.LayerChange.PropertyChanged ->
                layersById[change.layerId] = change.after
        }
    }
    for ((layerId, pick) in layerConflictPicks) {
        val theirLayer = theirs.layers.firstOrNull { it.id == layerId }
        val mineLayer = mine.layers.firstOrNull { it.id == layerId }
        val ancestorLayer = ancestor.layers.firstOrNull { it.id == layerId }
        when (pick) {
            ConflictResolution.TAKE_THEIRS ->
                if (theirLayer != null) layersById[layerId] = theirLayer else layersById.remove(layerId)
            ConflictResolution.KEEP_MINE ->
                if (mineLayer != null) layersById[layerId] = mineLayer else layersById.remove(layerId)
            ConflictResolution.SKIP ->
                if (ancestorLayer != null) layersById[layerId] = ancestorLayer else layersById.remove(layerId)
        }
    }

    // Cell-level changes after layers settled. Each layer's cell list rebuilds
    // from a (x, y) -> ChartCell map seeded from the post-layer-merge layer.
    val cellsByLayerXy =
        layersById
            .mapValues { (_, layer) ->
                layer.cells.associateBy { it.x to it.y }.toMutableMap()
            }.toMutableMap()

    for (change in autoFromTheirs) {
        val cells = cellsByLayerXy.getOrPut(change.layerId) { mutableMapOf() }
        when (change) {
            is io.github.b150005.knitnote.domain.model.CellChange.Added ->
                cells[change.cell.x to change.cell.y] = change.cell
            is io.github.b150005.knitnote.domain.model.CellChange.Removed ->
                cells.remove(change.cell.x to change.cell.y)
            is io.github.b150005.knitnote.domain.model.CellChange.Modified ->
                cells[change.after.x to change.after.y] = change.after
        }
    }

    for ((coord, pick) in conflictPicks) {
        // Code review HIGH-2 fix: if the layer was removed by
        // autoLayerFromTheirs / a layer-conflict pick of TAKE_THEIRS, the
        // layer is absent from `layersById` here. Without this guard,
        // `cellsByLayerXy.getOrPut` would silently recreate an orphan cell
        // bucket that the final `mergedLayers` build never iterates — the
        // SKIP-restore-ancestor outcome would silently disappear. Honor
        // SKIP / KEEP_MINE / TAKE_THEIRS resolutions only when the layer
        // still exists post-layer-merge; if not, the cell is correctly
        // gone with the layer.
        if (!layersById.containsKey(coord.layerId)) continue

        val cells = cellsByLayerXy.getOrPut(coord.layerId) { mutableMapOf() }
        val key = coord.x to coord.y
        val ancestorCell =
            ancestor.layers
                .firstOrNull { it.id == coord.layerId }
                ?.cells
                ?.firstOrNull { it.x == coord.x && it.y == coord.y }
        val theirCell =
            theirs.layers
                .firstOrNull { it.id == coord.layerId }
                ?.cells
                ?.firstOrNull { it.x == coord.x && it.y == coord.y }
        val mineCell =
            mine.layers
                .firstOrNull { it.id == coord.layerId }
                ?.cells
                ?.firstOrNull { it.x == coord.x && it.y == coord.y }
        val resolved =
            when (pick) {
                ConflictResolution.TAKE_THEIRS -> theirCell
                ConflictResolution.KEEP_MINE -> mineCell
                ConflictResolution.SKIP -> ancestorCell
            }
        if (resolved == null) {
            cells.remove(key)
        } else {
            cells[key] = resolved
        }
    }

    val mergedLayers =
        layersById.values.map { layer ->
            val cells = cellsByLayerXy[layer.id]?.values?.toList() ?: layer.cells
            layer.copy(cells = cells)
        }

    // Recompute extents on rect charts so a merge that shrinks the chart's
    // footprint does not retain stale max-x/y. Polar charts keep their
    // extents (rings + stitchesPerRing structure) since the resolution map
    // never changes ring counts in v1.
    val newExtents =
        when (val ext = mine.extents) {
            is ChartExtents.Rect -> recomputeRectExtents(mergedLayers)
            is ChartExtents.Polar -> ext
        }

    return mine.copy(
        layers = mergedLayers,
        extents = newExtents,
    )
}

private fun recomputeRectExtents(layers: List<ChartLayer>): ChartExtents.Rect {
    val allCells = layers.flatMap { it.cells }
    if (allCells.isEmpty()) return ChartExtents.Rect.EMPTY
    val minX = allCells.minOf { it.x }
    val maxX = allCells.maxOf { it.x + it.width - 1 }
    val minY = allCells.minOf { it.y }
    val maxY = allCells.maxOf { it.y + it.height - 1 }
    return ChartExtents.Rect(minX = minX, maxX = maxX, minY = minY, maxY = maxY)
}

/**
 * Per-conflict resolution. Skip picks the ancestor value (i.e. revert this
 * cell to its pre-fork state).
 */
enum class ConflictResolution {
    TAKE_THEIRS,
    KEEP_MINE,
    SKIP,
}
