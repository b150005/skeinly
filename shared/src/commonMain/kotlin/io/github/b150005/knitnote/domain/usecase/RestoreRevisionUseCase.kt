package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.repository.ChartRevisionRepository
import io.github.b150005.knitnote.domain.repository.StructuredChartRepository
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Restore a past revision as a new commit on top of the current tip
 * (Phase 37.4, ADR-013 §6).
 *
 * Additive, NOT destructive — the past revision stays in history, and a new
 * revision is appended whose drawing payload mirrors the restored one. The
 * restored revision becomes the parent of nothing; the new revision's parent
 * is the old tip (so `chart_documents.revision_id` advances to the new
 * revision).
 *
 * This is the same shape as a regular `update`: append a new revision +
 * advance the tip + advance the current branch. The use case constructs the
 * new chart from the restored revision's drawing fields and calls
 * `StructuredChartRepository.update(...)`, which handles all three.
 */
@OptIn(ExperimentalUuidApi::class)
class RestoreRevisionUseCase(
    private val revisionRepository: ChartRevisionRepository,
    private val chartRepository: StructuredChartRepository,
) {
    suspend operator fun invoke(
        patternId: String,
        revisionIdToRestore: String,
    ): UseCaseResult<StructuredChart> =
        try {
            val toRestore = revisionRepository.getRevision(revisionIdToRestore)
            if (toRestore == null) {
                UseCaseResult.Failure(UseCaseError.ResourceNotFound)
            } else {
                val current = chartRepository.getByPatternId(patternId)
                if (current == null) {
                    UseCaseResult.Failure(UseCaseError.ResourceNotFound)
                } else {
                    val now = Clock.System.now()
                    val restored =
                        current.copy(
                            // Drawing payload comes from the restored revision.
                            schemaVersion = toRestore.schemaVersion,
                            storageVariant = toRestore.storageVariant,
                            coordinateSystem = toRestore.coordinateSystem,
                            extents = toRestore.extents,
                            layers = toRestore.layers,
                            craftType = toRestore.craftType,
                            readingConvention = toRestore.readingConvention,
                            contentHash = toRestore.contentHash,
                            // Lineage: new revision is a child of the current
                            // tip — restoration is a forward commit, not a
                            // jump back into history.
                            revisionId = Uuid.random().toString(),
                            parentRevisionId = current.revisionId,
                            updatedAt = now,
                        )
                    UseCaseResult.Success(chartRepository.update(restored))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
