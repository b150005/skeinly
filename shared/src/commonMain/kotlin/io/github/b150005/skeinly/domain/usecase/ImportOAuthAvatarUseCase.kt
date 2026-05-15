package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.repository.AuthRepository
import io.github.b150005.skeinly.domain.repository.StorageOperations
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException

/**
 * Phase 26.6 (ADR-022 §6.6) — downloads a Google-supplied OAuth avatar
 * picture URL (`user_metadata.picture`) and routes it through the
 * existing avatar upload pipeline so the result is indistinguishable
 * from a user-uploaded avatar at the persistence layer.
 *
 * Flow:
 *   1. Validate the URL scheme (HTTPS-only) + host (allowlist of Google
 *      CDN hosts that Google's OAuth picture claims have been observed
 *      to use; rejecting unknown hosts prevents SSRF + content from
 *      attacker-controlled domains).
 *   2. HTTP GET the picture bytes via the injected Ktor client. Reject
 *      non-2xx responses + payloads larger than the 1MB cap shared with
 *      [UploadAvatarUseCase].
 *   3. Validate the bytes are a JPEG (current avatar pipeline is JPEG-
 *      only — Google's picture URLs return JPEG by default).
 *   4. Hand off to the existing avatar upload chain: storage upload →
 *      `profiles.avatar_url` patch. Same surface as a manual upload,
 *      so a re-import overwrites cleanly.
 *
 * Failure modes return [UseCaseResult.Failure] with mapped
 * [UseCaseError] codes so the ViewModel can surface specific copy
 * (network vs. invalid-image vs. too-large). Cancellation is rethrown
 * to keep structured concurrency intact.
 */
class ImportOAuthAvatarUseCase(
    private val httpClient: HttpClient,
    private val remoteStorage: StorageOperations?,
    private val authRepository: AuthRepository,
) {
    suspend operator fun invoke(pictureUrl: String): UseCaseResult<String> {
        val parsedUrl =
            try {
                Url(pictureUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                return UseCaseResult.Failure(UseCaseError.ImageInvalid)
            }
        if (parsedUrl.protocol.name.lowercase() != "https") {
            return UseCaseResult.Failure(UseCaseError.ImageInvalid)
        }
        val host = parsedUrl.host.lowercase()
        if (!isAllowedAvatarHost(host)) {
            return UseCaseResult.Failure(UseCaseError.ImageInvalid)
        }
        val storage =
            remoteStorage
                ?: return UseCaseResult.Failure(
                    UseCaseError.Network(IllegalStateException("Storage not available")),
                )
        val userId =
            authRepository.getCurrentUserId()
                ?: return UseCaseResult.Failure(
                    UseCaseError.Authentication(
                        IllegalStateException("Must be signed in to import an OAuth avatar"),
                    ),
                )

        val response: HttpResponse =
            try {
                httpClient.get(pictureUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return UseCaseResult.Failure(UseCaseError.Network(e))
            }
        if (response.status != HttpStatusCode.OK) {
            return UseCaseResult.Failure(
                UseCaseError.Network(
                    IllegalStateException("Avatar fetch returned ${response.status.value}"),
                ),
            )
        }
        val bytes: ByteArray =
            try {
                response.body<ByteArray>()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return UseCaseResult.Failure(UseCaseError.Network(e))
            }
        if (bytes.isEmpty()) {
            return UseCaseResult.Failure(UseCaseError.ImageInvalid)
        }
        if (bytes.size > MAX_AVATAR_BYTES) {
            return UseCaseResult.Failure(UseCaseError.ImageTooLarge)
        }
        if (!UploadChartImageUseCase.isValidJpeg(bytes)) {
            return UseCaseResult.Failure(UseCaseError.ImageInvalid)
        }

        // Reuse the existing storage layout convention from
        // [UploadAvatarUseCase] — `<user_id>/profile/<sanitized-filename>`
        // so RLS + public-URL rendering match the manual-upload path.
        val safeFileName = UploadChartImageUseCase.sanitizeFileName(DEFAULT_FILE_NAME)
        return try {
            val path = storage.upload(userId, AVATAR_SUBFOLDER, safeFileName, bytes)
            UseCaseResult.Success(storage.publicUrl(path))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UseCaseResult.Failure(e.toUseCaseError())
        }
    }

    private companion object {
        const val MAX_AVATAR_BYTES: Int = 1 * 1024 * 1024 // 1MB — matches UploadAvatarUseCase
        const val AVATAR_SUBFOLDER: String = "profile"
        const val DEFAULT_FILE_NAME: String = "oauth-avatar.jpg"

        /**
         * Google OAuth `picture` URLs have been observed to use these
         * hosts (collected from Google Identity Services docs and
         * empirical observation). Rejecting other hosts means a
         * compromised IDToken cannot redirect the downloader to an
         * attacker-controlled origin. Apple does not expose an avatar
         * URL so the host allowlist does not need an Apple entry.
         */
        fun isAllowedAvatarHost(host: String): Boolean {
            if (host.endsWith(".googleusercontent.com") || host == "googleusercontent.com") return true
            if (host.endsWith(".gstatic.com") || host == "gstatic.com") return true
            if (host == "lh3.google.com" || host.endsWith(".lh3.google.com")) return true
            return false
        }
    }
}
