package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.data.remote.FakeRemoteStorageDataSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Phase 26.6 (ADR-022 §6.6) — locks the URL validation + happy-path
 * import flow for [ImportOAuthAvatarUseCase]. Uses Ktor MockEngine to
 * inject controlled HTTP responses without standing up a network
 * stack. Reuses [FakeRemoteStorageDataSource] for the Storage seam.
 */
class ImportOAuthAvatarUseCaseTest {
    private val authRepository = FakeAuthRepository()

    /** Minimal valid JPEG byte sequence: SOI (0xFFD8FF) + EOI (0xFFD9). */
    private val sampleJpegBytes: ByteArray =
        byteArrayOf(
            0xFF.toByte(),
            0xD8.toByte(),
            0xFF.toByte(),
            0xE0.toByte(),
            // some filler so size >= 6
            0x00.toByte(),
            0xFF.toByte(),
            0xD9.toByte(),
        )

    private fun makeUseCase(
        responseStatus: HttpStatusCode = HttpStatusCode.OK,
        responseBody: ByteArray = sampleJpegBytes,
        storage: FakeRemoteStorageDataSource = FakeRemoteStorageDataSource(),
    ): Pair<ImportOAuthAvatarUseCase, FakeRemoteStorageDataSource> {
        val engine =
            MockEngine { _ ->
                respond(
                    content = ByteReadChannel(responseBody),
                    status = responseStatus,
                    headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
                )
            }
        val client = HttpClient(engine)
        return ImportOAuthAvatarUseCase(
            httpClient = client,
            remoteStorage = storage,
            authRepository = authRepository,
        ) to storage
    }

    @Test
    fun `happy path uploads JPEG and returns public URL`() =
        runTest {
            authRepository.setAuthState(
                io.github.b150005.skeinly.domain.model.AuthState
                    .Authenticated(userId = "user-1", email = null),
            )
            val (useCase, storage) = makeUseCase()
            val result =
                useCase("https://lh3.googleusercontent.com/a-/AOh14GhAtest")
            assertIs<UseCaseResult.Success<String>>(result)
            assertTrue(result.value.contains("user-1"))
            assertEquals(1, storage.getUploadedFileCount())
        }

    @Test
    fun `non-https URL is rejected as ImageInvalid`() =
        runTest {
            authRepository.setAuthState(
                io.github.b150005.skeinly.domain.model.AuthState
                    .Authenticated(userId = "user-1", email = null),
            )
            val (useCase, _) = makeUseCase()
            val result = useCase("http://googleusercontent.com/a/test")
            assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.ImageInvalid, result.error)
        }

    @Test
    fun `non-allowlisted host is rejected as ImageInvalid`() =
        runTest {
            authRepository.setAuthState(
                io.github.b150005.skeinly.domain.model.AuthState
                    .Authenticated(userId = "user-1", email = null),
            )
            val (useCase, _) = makeUseCase()
            val result = useCase("https://attacker.example.com/avatar.jpg")
            assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.ImageInvalid, result.error)
        }

    @Test
    fun `404 response surfaces Network error`() =
        runTest {
            authRepository.setAuthState(
                io.github.b150005.skeinly.domain.model.AuthState
                    .Authenticated(userId = "user-1", email = null),
            )
            val (useCase, _) =
                makeUseCase(responseStatus = HttpStatusCode.NotFound)
            val result = useCase("https://lh3.googleusercontent.com/a/test")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Network>(result.error)
        }

    @Test
    fun `non-JPEG bytes are rejected as ImageInvalid`() =
        runTest {
            authRepository.setAuthState(
                io.github.b150005.skeinly.domain.model.AuthState
                    .Authenticated(userId = "user-1", email = null),
            )
            val (useCase, _) =
                makeUseCase(responseBody = byteArrayOf(0x89.toByte(), 0x50.toByte()))
            val result = useCase("https://lh3.googleusercontent.com/a/test")
            assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.ImageInvalid, result.error)
        }

    @Test
    fun `unauthenticated user surfaces Authentication error`() =
        runTest {
            // Sign-out the FakeAuthRepository → getCurrentUserId returns null.
            authRepository.setAuthState(
                io.github.b150005.skeinly.domain.model.AuthState.Unauthenticated,
            )
            val (useCase, _) = makeUseCase()
            val result = useCase("https://lh3.googleusercontent.com/a/test")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Authentication>(result.error)
        }

    @Test
    fun `malformed URL is rejected as ImageInvalid`() =
        runTest {
            authRepository.setAuthState(
                io.github.b150005.skeinly.domain.model.AuthState
                    .Authenticated(userId = "user-1", email = null),
            )
            val (useCase, _) = makeUseCase()
            val result = useCase("not a url")
            assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.ImageInvalid, result.error)
        }

    @Test
    fun `empty response body is rejected as ImageInvalid`() =
        runTest {
            authRepository.setAuthState(
                io.github.b150005.skeinly.domain.model.AuthState
                    .Authenticated(userId = "user-1", email = null),
            )
            val (useCase, _) = makeUseCase(responseBody = ByteArray(0))
            val result = useCase("https://lh3.googleusercontent.com/a/test")
            assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.ImageInvalid, result.error)
        }

    @Test
    fun `oversized response body is rejected as ImageTooLarge`() =
        runTest {
            authRepository.setAuthState(
                io.github.b150005.skeinly.domain.model.AuthState
                    .Authenticated(userId = "user-1", email = null),
            )
            // 1 MB + 1 byte — just past the cap.
            val oversized = ByteArray(1 * 1024 * 1024 + 1) { 0x00 }
            // Need at least the JPEG SOI bytes so the size check fires
            // BEFORE the magic-bytes check.
            oversized[0] = 0xFF.toByte()
            oversized[1] = 0xD8.toByte()
            val (useCase, _) = makeUseCase(responseBody = oversized)
            val result = useCase("https://lh3.googleusercontent.com/a/test")
            assertIs<UseCaseResult.Failure>(result)
            assertEquals(UseCaseError.ImageTooLarge, result.error)
        }

    @Test
    fun `null storage operations short-circuits to Network error`() =
        runTest {
            authRepository.setAuthState(
                io.github.b150005.skeinly.domain.model.AuthState
                    .Authenticated(userId = "user-1", email = null),
            )
            val engine =
                MockEngine { _ ->
                    respond(
                        content = ByteReadChannel(sampleJpegBytes),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "image/jpeg"),
                    )
                }
            val useCase =
                ImportOAuthAvatarUseCase(
                    httpClient = HttpClient(engine),
                    remoteStorage = null,
                    authRepository = authRepository,
                )
            val result = useCase("https://lh3.googleusercontent.com/a/test")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Network>(result.error)
        }
}
