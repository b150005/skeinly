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
            return UseCaseResult.Failure(UseCaseError.Validation("Sharing requires cloud connectivity"))
        }

        val userId =
            authRepository.getCurrentUserId()
                ?: return UseCaseResult.Failure(UseCaseError.Validation("Must be signed in"))

        val share =
            shareRepository.getById(shareId)
                ?: return UseCaseResult.Failure(UseCaseError.NotFound("Share not found"))

        if (share.toUserId != userId) {
            return UseCaseResult.Failure(UseCaseError.Validation("Only the recipient can update share status"))
        }

        if (share.status != ShareStatus.PENDING) {
            return UseCaseResult.Failure(UseCaseError.Validation("Only pending shares can be accepted or declined"))
        }

        if (status != ShareStatus.ACCEPTED && status != ShareStatus.DECLINED) {
            return UseCaseResult.Failure(UseCaseError.Validation("Invalid target status"))
        }

        val updated = shareRepository.updateStatus(shareId, status)
        return UseCaseResult.Success(updated)
    }
}
