package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.repository.AuthRepository
import kotlinx.coroutines.CancellationException

private const val MIN_PASSWORD_LENGTH = 6
private const val MAX_PASSWORD_LENGTH = 72

/**
 * Updates the currently-authenticated user's password. Requires an active
 * session; the underlying Supabase call rejects unauthenticated requests.
 *
 * Password length policy mirrors Supabase's default (min 6, max 72 since
 * bcrypt truncates beyond 72 bytes).
 */
class UpdatePasswordUseCase(
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(newPassword: String): UseCaseResult<Unit> {
        if (authRepository.getCurrentUserId() == null) {
            return UseCaseResult.Failure(
                UseCaseError.Authentication(
                    IllegalStateException("Sign in required to change password"),
                ),
            )
        }
        if (newPassword.length < MIN_PASSWORD_LENGTH) {
            return UseCaseResult.Failure(
                UseCaseError.Validation("Password must be at least $MIN_PASSWORD_LENGTH characters"),
            )
        }
        if (newPassword.length > MAX_PASSWORD_LENGTH) {
            return UseCaseResult.Failure(
                UseCaseError.Validation("Password too long (max $MAX_PASSWORD_LENGTH characters)"),
            )
        }

        return try {
            authRepository.updatePassword(newPassword)
            UseCaseResult.Success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }
}
