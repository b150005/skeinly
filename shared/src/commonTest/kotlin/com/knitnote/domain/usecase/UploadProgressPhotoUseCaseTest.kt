package com.knitnote.domain.usecase

import com.knitnote.data.remote.FakeRemoteStorageDataSource
import com.knitnote.domain.model.AuthState
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UploadProgressPhotoUseCaseTest {
    private lateinit var storage: FakeRemoteStorageDataSource
    private lateinit var authRepository: FakeAuthRepository
    private lateinit var useCase: UploadProgressPhotoUseCase

    private val validJpegData = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(100)

    @BeforeTest
    fun setUp() {
        storage = FakeRemoteStorageDataSource()
        authRepository = FakeAuthRepository()
        authRepository.setAuthState(AuthState.Authenticated(userId = "user-123", email = "test@test.com"))
        useCase = UploadProgressPhotoUseCase(storage, authRepository)
    }

    @Test
    fun `upload valid photo returns storage path`() =
        kotlinx.coroutines.test.runTest {
            val result = useCase("project-1", validJpegData, "progress_1.jpg")

            assertIs<UseCaseResult.Success<String>>(result)
            assertEquals("user-123/project-1/progress_1.jpg", result.value)
            assertEquals(1, storage.getUploadedFileCount())
        }

    @Test
    fun `empty image data returns validation error`() =
        kotlinx.coroutines.test.runTest {
            val result = useCase("project-1", byteArrayOf(), "photo.jpg")

            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `oversized image returns validation error`() =
        kotlinx.coroutines.test.runTest {
            val oversized = ByteArray(3 * 1024 * 1024)
            val result = useCase("project-1", oversized, "big.jpg")

            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `non-JPEG image returns validation error`() =
        kotlinx.coroutines.test.runTest {
            val pngData = byteArrayOf(0x89.toByte(), 0x50, 0x4E) + ByteArray(100)
            val result = useCase("project-1", pngData, "photo.png")

            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `null storage returns network error`() =
        kotlinx.coroutines.test.runTest {
            val noStorageUseCase = UploadProgressPhotoUseCase(null, authRepository)
            val result = noStorageUseCase("project-1", validJpegData, "photo.jpg")

            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Network>(result.error)
        }

    @Test
    fun `no auth returns validation error`() =
        kotlinx.coroutines.test.runTest {
            authRepository.setAuthState(AuthState.Unauthenticated)
            val result = useCase("project-1", validJpegData, "photo.jpg")

            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `filename is sanitized`() =
        kotlinx.coroutines.test.runTest {
            val result = useCase("project-1", validJpegData, "my photo (1).jpg")

            assertIs<UseCaseResult.Success<String>>(result)
            assertTrue(result.value.endsWith("my_photo__1_.jpg"))
        }

    @Test
    fun `storage exception returns failure`() =
        kotlinx.coroutines.test.runTest {
            storage.uploadError = RuntimeException("Upload failed")
            val result = useCase("project-1", validJpegData, "photo.jpg")

            assertIs<UseCaseResult.Failure>(result)
        }
}
