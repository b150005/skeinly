package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.CraftType
import io.github.b150005.knitnote.domain.model.ReadingConvention
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.repository.StructuredChartRepository
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Updates the drawable content (extents + layers) of an existing chart.
 * Produces a new revision id and recomputes the content hash. Callers are
 * expected to build the next `layers`/`extents` via `copy()` on the current
 * chart snapshot and pass them here.
 */
class UpdateStructuredChartUseCase(
    private val repository: StructuredChartRepository,
    private val json: Json,
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend operator fun invoke(
        current: StructuredChart,
        extents: ChartExtents = current.extents,
        layers: List<ChartLayer> = current.layers,
        craftType: CraftType = current.craftType,
        readingConvention: ReadingConvention = current.readingConvention,
    ): UseCaseResult<StructuredChart> =
        try {
            val newHash = StructuredChart.computeContentHash(extents, layers, json)
            // Short-circuit only when every persisted field matches. Metadata-only
            // changes (craft/reading) do not alter the content hash but still need
            // to round-trip to storage and bump the schema version to v2.
            val metadataUnchanged =
                craftType == current.craftType &&
                    readingConvention == current.readingConvention &&
                    current.schemaVersion == StructuredChart.CURRENT_SCHEMA_VERSION
            if (newHash == current.contentHash &&
                extents == current.extents &&
                layers == current.layers &&
                metadataUnchanged
            ) {
                UseCaseResult.Success(current)
            } else {
                val updated =
                    current.copy(
                        schemaVersion = StructuredChart.CURRENT_SCHEMA_VERSION,
                        extents = extents,
                        layers = layers,
                        revisionId = Uuid.random().toString(),
                        parentRevisionId = current.revisionId,
                        contentHash = newHash,
                        updatedAt = Clock.System.now(),
                        craftType = craftType,
                        readingConvention = readingConvention,
                    )
                UseCaseResult.Success(repository.update(updated))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
