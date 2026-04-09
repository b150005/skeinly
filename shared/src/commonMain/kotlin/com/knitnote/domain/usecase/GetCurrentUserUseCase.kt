package com.knitnote.domain.usecase

import com.knitnote.domain.model.User
import com.knitnote.domain.repository.AuthRepository
import com.knitnote.domain.repository.UserRepository

class GetCurrentUserUseCase(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository?,
) {

    suspend operator fun invoke(): UseCaseResult<User> {
        val userId = authRepository.getCurrentUserId()
            ?: return UseCaseResult.Failure(
                UseCaseError.Validation("Must be signed in to view profile"),
            )

        if (userRepository == null) {
            return UseCaseResult.Failure(
                UseCaseError.Validation("Profile requires cloud connectivity"),
            )
        }

        val user = userRepository.getById(userId)
            ?: return UseCaseResult.Failure(
                UseCaseError.NotFound("Profile not found"),
            )

        return UseCaseResult.Success(user)
    }
}
