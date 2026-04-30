package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.LocalUser
import io.github.b150005.skeinly.domain.model.ProjectSegment
import io.github.b150005.skeinly.domain.model.SegmentState
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.ProjectSegmentRepository
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock

/**
 * Forces a segment to [SegmentState.DONE] regardless of its prior state,
 * per PRD AC-3.1 (long-press → done). Idempotent if already `done`.
 */
class MarkSegmentDoneUseCase(
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
            val segment =
                existing?.copy(state = SegmentState.DONE, updatedAt = clock.now())
                    ?: ProjectSegment(
                        id = id,
                        projectId = projectId,
                        layerId = layerId,
                        cellX = cellX,
                        cellY = cellY,
                        state = SegmentState.DONE,
                        ownerId = authRepository?.getCurrentUserId() ?: LocalUser.ID,
                        updatedAt = clock.now(),
                    )
            repository.upsert(segment)
            UseCaseResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
