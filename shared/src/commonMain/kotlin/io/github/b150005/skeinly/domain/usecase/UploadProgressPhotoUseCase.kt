package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.StorageOperations

class UploadProgressPhotoUseCase(
    private val remoteStorage: StorageOperations?,
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(
        projectId: String,
        imageData: ByteArray,
        fileName: String,
    ): UseCaseResult<String> {
        if (imageData.isEmpty()) {
            return UseCaseResult.Failure(UseCaseError.ImageInvalid)
        }
        if (imageData.size > MAX_IMAGE_SIZE) {
            return UseCaseResult.Failure(
                UseCaseError.ImageTooLarge,
            )
        }

        val storage =
            remoteStorage
                ?: return UseCaseResult.Failure(
                    UseCaseError.Network(IllegalStateException("Storage not available")),
                )

        return try {
            val userId =
                authRepository.getCurrentUserId()
                    ?: return UseCaseResult.Failure(
                        UseCaseError.SignInRequired,
                    )

            if (!UploadChartImageUseCase.isValidJpeg(imageData)) {
                return UseCaseResult.Failure(
                    UseCaseError.ImageInvalid,
                )
            }

            val safeFileName = UploadChartImageUseCase.sanitizeFileName(fileName)
            val storagePath = storage.upload(userId, projectId, safeFileName, imageData)
            UseCaseResult.Success(storagePath)
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }

    companion object {
        const val MAX_IMAGE_SIZE = 2 * 1024 * 1024 // 2MB
    }
}
