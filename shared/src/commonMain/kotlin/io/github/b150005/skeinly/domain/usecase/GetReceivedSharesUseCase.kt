package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.Share
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.ShareRepository

class GetReceivedSharesUseCase(
    private val shareRepository: ShareRepository?,
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(): UseCaseResult<List<Share>> {
        if (shareRepository == null) {
            return UseCaseResult.Failure(UseCaseError.RequiresConnectivity)
        }

        val userId =
            authRepository.getCurrentUserId()
                ?: return UseCaseResult.Failure(UseCaseError.SignInRequired)

        return UseCaseResult.Success(shareRepository.getReceivedByUserId(userId))
    }
}
