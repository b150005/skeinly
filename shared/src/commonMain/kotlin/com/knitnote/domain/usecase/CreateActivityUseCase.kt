package com.knitnote.domain.usecase

import com.knitnote.domain.model.Activity
import com.knitnote.domain.model.ActivityTargetType
import com.knitnote.domain.model.ActivityType
import com.knitnote.domain.repository.ActivityRepository
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Internal use case for recording activity events.
 * Called by other use cases (CreateComment, ShareProject, etc.) after successful operations.
 * Best-effort: silently no-ops when [activityRepository] is null or when the write fails.
 */
class CreateActivityUseCase(
    private val activityRepository: ActivityRepository?,
) {

    @OptIn(ExperimentalUuidApi::class)
    suspend operator fun invoke(
        userId: String,
        type: ActivityType,
        targetType: ActivityTargetType,
        targetId: String,
        metadata: String? = null,
    ) {
        if (activityRepository == null) return
        val activity = Activity(
            id = Uuid.random().toString(),
            userId = userId,
            type = type,
            targetType = targetType,
            targetId = targetId,
            metadata = metadata,
            createdAt = Clock.System.now(),
        )
        try {
            activityRepository.create(activity)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Activity recording is best-effort; failures must not abort the primary operation.
        }
    }
}
