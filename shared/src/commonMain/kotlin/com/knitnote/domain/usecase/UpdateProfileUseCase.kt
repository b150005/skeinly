package com.knitnote.domain.usecase

import com.knitnote.domain.model.User
import com.knitnote.domain.repository.AuthRepository
import com.knitnote.domain.repository.UserRepository

private const val MAX_DISPLAY_NAME_LENGTH = 50
private const val MAX_BIO_LENGTH = 500

class UpdateProfileUseCase(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
) {

    suspend operator fun invoke(
        displayName: String,
        bio: String?,
        avatarUrl: String?,
    ): UseCaseResult<User> {
        val userId = authRepository.getCurrentUserId()
            ?: return UseCaseResult.Failure(
                UseCaseError.Validation("Must be signed in to update profile"),
            )

        val trimmedName = displayName.trim()
        if (trimmedName.isEmpty()) {
            return UseCaseResult.Failure(
                UseCaseError.Validation("Display name cannot be empty"),
            )
        }
        if (trimmedName.length > MAX_DISPLAY_NAME_LENGTH) {
            return UseCaseResult.Failure(
                UseCaseError.Validation("Display name cannot exceed $MAX_DISPLAY_NAME_LENGTH characters"),
            )
        }

        val trimmedBio = bio?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedBio != null && trimmedBio.length > MAX_BIO_LENGTH) {
            return UseCaseResult.Failure(
                UseCaseError.Validation("Bio cannot exceed $MAX_BIO_LENGTH characters"),
            )
        }

        val currentUser = userRepository.getById(userId)
            ?: return UseCaseResult.Failure(
                UseCaseError.NotFound("Profile not found"),
            )

        val updatedUser = currentUser.copy(
            displayName = trimmedName,
            bio = trimmedBio,
            avatarUrl = avatarUrl,
        )

        val saved = userRepository.update(updatedUser)
        return UseCaseResult.Success(saved)
    }
}
