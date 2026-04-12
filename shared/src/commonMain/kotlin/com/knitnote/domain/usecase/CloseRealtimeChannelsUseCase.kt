package com.knitnote.domain.usecase

import com.knitnote.domain.repository.ActivityRepository
import com.knitnote.domain.repository.CommentRepository
import com.knitnote.domain.repository.ShareRepository

class CloseRealtimeChannelsUseCase(
    private val shareRepository: ShareRepository?,
    private val commentRepository: CommentRepository?,
    private val activityRepository: ActivityRepository?,
) {
    suspend operator fun invoke() {
        val errors =
            listOfNotNull(
                runCatching { shareRepository?.closeChannel() }.exceptionOrNull(),
                runCatching { commentRepository?.closeChannel() }.exceptionOrNull(),
                runCatching { activityRepository?.closeChannel() }.exceptionOrNull(),
            )
        if (errors.isNotEmpty()) {
            throw errors.first().apply {
                errors.drop(1).forEach { addSuppressed(it) }
            }
        }
    }
}
