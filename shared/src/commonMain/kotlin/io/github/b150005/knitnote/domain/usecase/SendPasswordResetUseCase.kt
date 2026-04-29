package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.repository.AuthRepository
import kotlinx.coroutines.CancellationException

/**
 * Sends a password-reset email via Supabase auth. The recipient receives
 * a one-time link to reset their password through Supabase's default
 * web reset page. The user is NOT required to be signed in.
 */
class SendPasswordResetUseCase(
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(email: String): UseCaseResult<Unit> {
        val trimmed = email.trim()
        if (trimmed.isEmpty()) {
            return UseCaseResult.Failure(UseCaseError.Validation("Email is required"))
        }
        if (!trimmed.contains("@") || !trimmed.contains(".")) {
            return UseCaseResult.Failure(UseCaseError.Validation("Invalid email address"))
        }

        return try {
            authRepository.sendPasswordResetEmail(trimmed)
            UseCaseResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }
}
