package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.LocalUser
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.ChartLayer
import io.github.b150005.knitnote.domain.model.CoordinateSystem
import io.github.b150005.knitnote.domain.model.CraftType
import io.github.b150005.knitnote.domain.model.ReadingConvention
import io.github.b150005.knitnote.domain.model.StorageVariant
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.StructuredChartRepository
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

class CreateStructuredChartUseCase(
    private val repository: StructuredChartRepository,
    private val authRepository: AuthRepository,
    private val json: Json,
) {
    @OptIn(ExperimentalUuidApi::class)
    suspend operator fun invoke(
        patternId: String,
        coordinateSystem: CoordinateSystem = CoordinateSystem.RECT_GRID,
        extents: ChartExtents =
            if (coordinateSystem == CoordinateSystem.RECT_GRID) {
                ChartExtents.Rect.EMPTY
            } else {
                ChartExtents.Polar(rings = 0, stitchesPerRing = emptyList())
            },
        layers: List<ChartLayer> = emptyList(),
        craftType: CraftType = CraftType.KNIT,
        readingConvention: ReadingConvention = ReadingConvention.KNIT_FLAT,
    ): UseCaseResult<StructuredChart> {
        if (patternId.isBlank()) {
            return UseCaseResult.Failure(UseCaseError.Validation("patternId must not be blank"))
        }
        if (!extentsMatchCoordinateSystem(coordinateSystem, extents)) {
            return UseCaseResult.Failure(
                UseCaseError.Validation(
                    "extents ${extents::class.simpleName} does not match coordinate system $coordinateSystem",
                ),
            )
        }
        return try {
            val existing = repository.getByPatternId(patternId)
            if (existing != null) {
                return UseCaseResult.Failure(
                    UseCaseError.Validation("A structured chart already exists for pattern $patternId"),
                )
            }
            val ownerId = authRepository.getCurrentUserId() ?: LocalUser.ID
            val now = Clock.System.now()
            val chart =
                StructuredChart(
                    id = Uuid.random().toString(),
                    patternId = patternId,
                    ownerId = ownerId,
                    schemaVersion = StructuredChart.CURRENT_SCHEMA_VERSION,
                    storageVariant = StorageVariant.INLINE,
                    coordinateSystem = coordinateSystem,
                    extents = extents,
                    layers = layers,
                    revisionId = Uuid.random().toString(),
                    parentRevisionId = null,
                    contentHash = StructuredChart.computeContentHash(extents, layers, json),
                    createdAt = now,
                    updatedAt = now,
                    craftType = craftType,
                    readingConvention = readingConvention,
                )
            UseCaseResult.Success(repository.create(chart))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }

    private fun extentsMatchCoordinateSystem(
        coordinateSystem: CoordinateSystem,
        extents: ChartExtents,
    ): Boolean =
        when (coordinateSystem) {
            CoordinateSystem.RECT_GRID -> extents is ChartExtents.Rect
            CoordinateSystem.POLAR_ROUND -> extents is ChartExtents.Polar
        }
}
