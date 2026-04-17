package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.PatternRepository
import io.github.b150005.knitnote.domain.repository.StorageOperations
import kotlin.time.Clock

class UploadChartImageUseCase(
    private val patternRepository: PatternRepository,
    private val remoteStorage: StorageOperations?,
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(
        patternId: String,
        imageData: ByteArray,
        fileName: String,
    ): UseCaseResult<Pattern> {
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

        val pattern =
            patternRepository.getById(patternId)
                ?: return UseCaseResult.Failure(UseCaseError.NotFound("Pattern not found"))

        return try {
            val userId =
                authRepository.getCurrentUserId()
                    ?: return UseCaseResult.Failure(
                        UseCaseError.Validation("Must be signed in to upload images"),
                    )

            if (!isValidJpeg(imageData)) {
                return UseCaseResult.Failure(
                    UseCaseError.Validation("File is not a valid JPEG image"),
                )
            }

            val safeFileName = sanitizeFileName(fileName)
            val storagePath = storage.upload(userId, patternId, safeFileName, imageData)
            val updatedPattern =
                pattern.copy(
                    chartImageUrls = pattern.chartImageUrls + storagePath,
                    updatedAt = Clock.System.now(),
                )
            patternRepository.update(updatedPattern)
            UseCaseResult.Success(updatedPattern)
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }

    companion object {
        const val MAX_IMAGE_SIZE = 2 * 1024 * 1024 // 2MB

        private val SAFE_FILENAME_REGEX = Regex("[^a-zA-Z0-9._-]")
        private val DOT_DOT_REGEX = Regex("\\.{2,}")
        private val ALLOWED_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")

        fun sanitizeFileName(fileName: String): String {
            val sanitized =
                fileName
                    .replace(SAFE_FILENAME_REGEX, "_")
                    .replace(DOT_DOT_REGEX, "_")
                    .trimStart('.')

            // Normalize double extensions: strip to baseName + first allowed image extension
            val parts = sanitized.split(".")
            if (parts.size > 2) {
                val baseName = parts.first()
                val firstAllowed = parts.drop(1).firstOrNull { it.lowercase() in ALLOWED_EXTENSIONS }
                return if (firstAllowed != null) "$baseName.$firstAllowed" else baseName
            }
            return sanitized
        }

        fun isValidJpeg(data: ByteArray): Boolean =
            data.size >= 6 &&
                data[0] == 0xFF.toByte() &&
                data[1] == 0xD8.toByte() &&
                data[2] == 0xFF.toByte() &&
                data[data.size - 2] == 0xFF.toByte() &&
                data[data.size - 1] == 0xD9.toByte()
    }
}
