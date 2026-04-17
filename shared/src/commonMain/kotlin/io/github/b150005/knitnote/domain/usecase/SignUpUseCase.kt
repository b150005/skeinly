package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.repository.AuthRepository
import kotlin.coroutines.cancellation.CancellationException

class SignUpUseCase(
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(
        email: String,
        password: String,
    ): UseCaseResult<Unit> =
        try {
            if (email.isBlank()) {
                return UseCaseResult.Failure(UseCaseError.Validation("Email is required"))
            }
            if (password.length < 6) {
                return UseCaseResult.Failure(UseCaseError.Validation("Password must be at least 6 characters"))
            }
            authRepository.signUpWithEmail(email, password)
            UseCaseResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
