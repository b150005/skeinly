package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.User
import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.UserRepository

class GetCurrentUserUseCase(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
) {
    suspend operator fun invoke(): UseCaseResult<User> {
        val userId =
            authRepository.getCurrentUserId()
                ?: return UseCaseResult.Failure(
                    UseCaseError.SignInRequired,
                )

        val user =
            userRepository.getById(userId)
                ?: return UseCaseResult.Failure(
                    UseCaseError.ResourceNotFound,
                )

        return UseCaseResult.Success(user)
    }
}
