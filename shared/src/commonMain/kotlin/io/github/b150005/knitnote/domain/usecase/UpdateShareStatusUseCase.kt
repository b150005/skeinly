package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.Share
import io.github.b150005.knitnote.domain.model.ShareStatus
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.ShareRepository

class UpdateShareStatusUseCase(
    private val shareRepository: ShareRepository?,
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(
        shareId: String,
        status: ShareStatus,
    ): UseCaseResult<Share> {
        if (shareRepository == null) {
            return UseCaseResult.Failure(UseCaseError.RequiresConnectivity)
        }

        val userId =
            authRepository.getCurrentUserId()
                ?: return UseCaseResult.Failure(UseCaseError.SignInRequired)

        val share =
            shareRepository.getById(shareId)
                ?: return UseCaseResult.Failure(UseCaseError.ResourceNotFound)

        if (share.toUserId != userId) {
            return UseCaseResult.Failure(UseCaseError.PermissionDenied)
        }

        if (share.status != ShareStatus.PENDING) {
            return UseCaseResult.Failure(UseCaseError.OperationNotAllowed)
        }

        if (status != ShareStatus.ACCEPTED && status != ShareStatus.DECLINED) {
            return UseCaseResult.Failure(UseCaseError.OperationNotAllowed)
        }

        val updated = shareRepository.updateStatus(shareId, status)
        return UseCaseResult.Success(updated)
    }
}
