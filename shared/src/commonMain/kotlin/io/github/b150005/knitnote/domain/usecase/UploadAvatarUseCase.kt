package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.repository.AuthRepository
import io.github.b150005.knitnote.domain.repository.StorageOperations
import kotlinx.coroutines.CancellationException

private const val MAX_AVATAR_SIZE = 1 * 1024 * 1024 // 1MB — avatars are small thumbnails
private const val AVATAR_SUBFOLDER = "profile"

/**
 * Uploads a JPEG avatar image for the current user to the public `avatars`
 * Storage bucket. Storage path follows the per-user-folder convention
 * `<user_id>/profile/<sanitized-filename>` so the bucket-level RLS
 * policies (migration 019) restrict writes to the owner.
 *
 * On success, the public URL is patched into the User row via
 * [UserRepository.updateProfile] so other clients see the new avatar
 * via the standard profile-fetch path. Returns the public URL on success.
 */
class UploadAvatarUseCase(
    private val remoteStorage: StorageOperations?,
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(
        imageData: ByteArray,
        fileName: String,
    ): UseCaseResult<String> {
        if (imageData.isEmpty()) {
            return UseCaseResult.Failure(UseCaseError.Validation("Image data is empty"))
        }
        if (imageData.size > MAX_AVATAR_SIZE) {
            return UseCaseResult.Failure(
                UseCaseError.Validation(
                    "Avatar exceeds maximum size of ${MAX_AVATAR_SIZE / 1024 / 1024}MB",
                ),
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
                        UseCaseError.Authentication(
                            IllegalStateException("Must be signed in to upload an avatar"),
                        ),
                    )

            if (!UploadChartImageUseCase.isValidJpeg(imageData)) {
                return UseCaseResult.Failure(
                    UseCaseError.Validation("File is not a valid JPEG image"),
                )
            }

            val safeFileName = UploadChartImageUseCase.sanitizeFileName(fileName)
            val storagePath = storage.upload(userId, AVATAR_SUBFOLDER, safeFileName, imageData)

            // Public bucket — return the persistent public URL so the
            // ViewModel can patch it into User.avatarUrl via UpdateProfileUseCase
            // without needing to re-sign per request. (For private buckets like
            // chart-images / progress-photos we use createSignedUrl instead.)
            UseCaseResult.Success(storage.publicUrl(storagePath))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }
}
