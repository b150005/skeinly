package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.StorageOperations

class DeleteProgressPhotoUseCase(
    private val remoteStorage: StorageOperations?,
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(photoPath: String): UseCaseResult<Unit> {
        val storage =
            remoteStorage
                ?: return UseCaseResult.Failure(
                    UseCaseError.Network(IllegalStateException("Storage not available")),
                )

        val userId =
            authRepository.getCurrentUserId()
                ?: return UseCaseResult.Failure(
                    UseCaseError.Validation("Must be signed in to delete photos"),
                )

        val pathSegments = photoPath.split("/")
        if (pathSegments.isEmpty() || pathSegments[0] != userId) {
            return UseCaseResult.Failure(UseCaseError.Validation("Cannot delete photo belonging to another user"))
        }

        return try {
            storage.delete(listOf(photoPath))
            UseCaseResult.Success(Unit)
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }
}
