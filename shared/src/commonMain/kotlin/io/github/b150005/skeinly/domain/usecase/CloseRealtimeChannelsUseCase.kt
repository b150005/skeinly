package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.repository.ActivityRepository
import io.github.b150005.skeinly.domain.repository.CommentRepository
import io.github.b150005.skeinly.domain.repository.ShareRepository

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
