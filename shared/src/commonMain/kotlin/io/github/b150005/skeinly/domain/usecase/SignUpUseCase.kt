package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.repository.AuthRepository
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
                return UseCaseResult.Failure(UseCaseError.FieldRequired)
            }
            if (password.length < 6) {
                return UseCaseResult.Failure(UseCaseError.PasswordTooShort)
            }
            authRepository.signUpWithEmail(email, password)
            UseCaseResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
}
