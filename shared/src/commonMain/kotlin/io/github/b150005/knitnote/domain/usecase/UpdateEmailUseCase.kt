package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.repository.AuthRepository
import kotlinx.coroutines.CancellationException

/**
 * Initiates an email change for the current user. Supabase sends a
 * verification email to the new address; the change does not take effect
 * until the user clicks the verification link. The current session
 * remains valid throughout the change-pending state.
 */
class UpdateEmailUseCase(
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(newEmail: String): UseCaseResult<Unit> {
        if (authRepository.getCurrentUserId() == null) {
            return UseCaseResult.Failure(
                UseCaseError.Authentication(
                    IllegalStateException("Sign in required to change email"),
                ),
            )
        }

        val trimmed = newEmail.trim()
        if (trimmed.isEmpty()) {
            return UseCaseResult.Failure(UseCaseError.Validation("Email is required"))
        }
        if (!trimmed.contains("@") || !trimmed.contains(".")) {
            return UseCaseResult.Failure(UseCaseError.Validation("Invalid email address"))
        }

        return try {
            authRepository.updateEmail(trimmed)
            UseCaseResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }
}
