package com.knitnote.domain.usecase

import com.knitnote.domain.model.Share
import com.knitnote.domain.repository.AuthRepository
import com.knitnote.domain.repository.ShareRepository

class GetReceivedSharesUseCase(
    private val shareRepository: ShareRepository?,
    private val authRepository: AuthRepository,
) {

    suspend operator fun invoke(): UseCaseResult<List<Share>> {
        if (shareRepository == null) {
            return UseCaseResult.Failure(UseCaseError.Validation("Sharing requires cloud connectivity"))
        }

        val userId = authRepository.getCurrentUserId()
            ?: return UseCaseResult.Failure(UseCaseError.Validation("Must be signed in to view shares"))

        return UseCaseResult.Success(shareRepository.getReceivedByUserId(userId))
    }
}
