package com.knitnote.domain.usecase

import com.knitnote.domain.repository.AuthRepository
import com.knitnote.domain.repository.StorageOperations

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
            return UseCaseResult.Failure(UseCaseError.Validation("Image data is empty"))
        }
        if (imageData.size > MAX_IMAGE_SIZE) {
            return UseCaseResult.Failure(
                UseCaseError.Validation("Image exceeds maximum size of ${MAX_IMAGE_SIZE / 1024 / 1024}MB"),
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
                        UseCaseError.Validation("Must be signed in to upload photos"),
                    )

            if (!UploadChartImageUseCase.isValidJpeg(imageData)) {
                return UseCaseResult.Failure(
                    UseCaseError.Validation("File is not a valid JPEG image"),
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
