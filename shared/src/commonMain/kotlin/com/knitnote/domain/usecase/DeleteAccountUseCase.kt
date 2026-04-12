package com.knitnote.domain.usecase

import com.knitnote.domain.repository.ActivityRepository
import com.knitnote.domain.repository.AuthRepository
import com.knitnote.domain.repository.CommentRepository
import com.knitnote.domain.repository.ShareRepository
import kotlinx.coroutines.CancellationException

class DeleteAccountUseCase(
    private val authRepository: AuthRepository,
    private val shareRepository: ShareRepository?,
    private val commentRepository: CommentRepository?,
    private val activityRepository: ActivityRepository?,
) {
    suspend operator fun invoke(): UseCaseResult<Unit> =
        try {
            // Close Realtime channels before deletion to prevent stale subscriptions
            shareRepository?.closeChannel()
            commentRepository?.closeChannel()
            activityRepository?.closeChannel()

            authRepository.deleteAccount()
            UseCaseResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
