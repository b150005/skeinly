package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.LocalUser
import io.github.b150005.knitnote.domain.model.ProjectSegment
import io.github.b150005.knitnote.domain.model.SegmentState
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.ProjectSegmentRepository
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

/**
 * Batch "mark row done" — forces every drawn cell in [row] across all visible
 * layers to [SegmentState.DONE]. Per ADR-011 §4, the implementation is a
 * per-segment upsert loop rather than a bulk repository API; the tradeoff
 * table in that section documents the choice.
 *
 * [row] is the chart y-coordinate on rect charts or the ring index on polar
 * charts — both map to `ChartCell.y` without reinterpretation per ADR-010 §4.
 *
 * Invisible layers are skipped so a user toggling off a reference layer does
 * not accidentally have its cells counted as "row done." Cells that do not
 * lie on [row] are left untouched. If [patternId] has no chart or the chart
 * has no cells on [row], the call succeeds as a no-op.
 */
class MarkRowSegmentsDoneUseCase(
    private val repository: ProjectSegmentRepository,
    private val getStructuredChart: GetStructuredChartByPatternIdUseCase,
    private val authRepository: AuthRepository?,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(
        patternId: String,
        projectId: String,
        row: Int,
    ): UseCaseResult<Unit> =
        try {
            when (val chartResult = getStructuredChart(patternId)) {
                is UseCaseResult.Failure -> chartResult
                is UseCaseResult.Success -> {
                    val chart = chartResult.value
                    if (chart == null) {
                        UseCaseResult.Success(Unit)
                    } else {
                        val ownerId = authRepository?.getCurrentUserId() ?: LocalUser.ID
                        val now = clock.now()
                        chart.layers.forEach { layer ->
                            if (!layer.visible) return@forEach
                            layer.cells.forEach inner@{ cell ->
                                if (cell.y != row) return@inner
                                val id = ProjectSegment.buildId(projectId, layer.id, cell.x, cell.y)
                                // `getById` + `copy` mirrors the pattern from
                                // `MarkSegmentDoneUseCase`: preserving the stored `ownerId`
                                // matters when a segment was authored offline under
                                // `LocalUser.ID` and later synced under an authenticated
                                // user — flipping ownerId on a "mark done" would rewrite
                                // authorship semantics. The extra per-cell read is N round
                                // trips for an N-stitch row; acceptable per ADR-011 §4's
                                // explicit "per-segment loop" tradeoff.
                                val existing = repository.getById(id)
                                val segment =
                                    existing?.copy(state = SegmentState.DONE, updatedAt = now)
                                        ?: ProjectSegment(
                                            id = id,
                                            projectId = projectId,
                                            layerId = layer.id,
                                            cellX = cell.x,
                                            cellY = cell.y,
                                            state = SegmentState.DONE,
                                            ownerId = ownerId,
                                            updatedAt = now,
                                        )
                                repository.upsert(segment)
                            }
                        }
                        UseCaseResult.Success(Unit)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
