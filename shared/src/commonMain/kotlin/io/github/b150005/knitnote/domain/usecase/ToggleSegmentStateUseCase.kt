package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.LocalUser
import io.github.b150005.knitnote.domain.model.ProjectSegment
import io.github.b150005.knitnote.domain.model.SegmentState
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.ProjectSegmentRepository
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

/**
 * Cycles a segment's state: todo → wip → done → todo (deletion).
 *
 * Per PRD AC-2.1..AC-2.3:
 * - Tap on todo (absent row) inserts `wip`.
 * - Tap on `wip` upgrades to `done`.
 * - Tap on `done` deletes the row (state returns to implicit todo).
 *
 * Hit-testing, empty-cell no-ops (AC-2.5) and invisible-layer no-ops (AC-2.6)
 * are viewmodel concerns — by the time this use case is called, the caller
 * has already resolved a drawn-cell coordinate.
 */
class ToggleSegmentStateUseCase(
    private val repository: ProjectSegmentRepository,
    private val authRepository: AuthRepository?,
    private val clock: Clock = Clock.System,
) {
    suspend operator fun invoke(
        projectId: String,
        layerId: String,
        cellX: Int,
        cellY: Int,
    ): UseCaseResult<Unit> =
        try {
            val id = ProjectSegment.buildId(projectId, layerId, cellX, cellY)
            val existing = repository.getById(id)
            when (existing?.state) {
                null -> {
                    repository.upsert(
                        ProjectSegment(
                            id = id,
                            projectId = projectId,
                            layerId = layerId,
                            cellX = cellX,
                            cellY = cellY,
                            state = SegmentState.WIP,
                            ownerId = authRepository?.getCurrentUserId() ?: LocalUser.ID,
                            updatedAt = clock.now(),
                        ),
                    )
                }
                SegmentState.WIP -> {
                    repository.upsert(existing.copy(state = SegmentState.DONE, updatedAt = clock.now()))
                }
                SegmentState.DONE -> {
                    repository.resetSegment(id)
                }
            }
            UseCaseResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
