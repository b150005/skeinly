package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.data.remote.FakeRemoteStorageDataSource
import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Visibility
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock

class UploadChartImageUseCaseTest {
    private lateinit var patternRepo: FakePatternRepository
    private lateinit var storage: FakeRemoteStorageDataSource
    private lateinit var authRepo: FakeAuthRepository
    private lateinit var useCase: UploadChartImageUseCase

    private fun validJpegBytes(size: Int = 1024): ByteArray {
        val bytes = ByteArray(size)
        // JPEG SOI: FF D8 FF
        bytes[0] = 0xFF.toByte()
        bytes[1] = 0xD8.toByte()
        bytes[2] = 0xFF.toByte()
        // JPEG EOI: FF D9
        bytes[size - 2] = 0xFF.toByte()
        bytes[size - 1] = 0xD9.toByte()
        return bytes
    }

    @BeforeTest
    fun setUp() {
        patternRepo = FakePatternRepository()
        storage = FakeRemoteStorageDataSource()
        authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated(userId = "user-1", email = "test@test.com"))
        useCase = UploadChartImageUseCase(patternRepo, storage, authRepo)
    }

    private fun createTestPattern(
        id: String = "pattern-1",
        chartImageUrls: List<String> = emptyList(),
    ): Pattern =
        Pattern(
            id = id,
            ownerId = "user-1",
            title = "Test Pattern",
            description = null,
            difficulty = Difficulty.BEGINNER,
            gauge = null,
            yarnInfo = null,
            needleSize = null,
            chartImageUrls = chartImageUrls,
            visibility = Visibility.PRIVATE,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )

    @Test
    fun `upload with valid data returns success with updated pattern`() =
        runTest {
            val pattern = createTestPattern()
            patternRepo.create(pattern)

            val result = useCase("pattern-1", validJpegBytes(), "chart.jpg")

            val success = assertIs<UseCaseResult.Success<Pattern>>(result)
            assertEquals(1, success.value.chartImageUrls.size)
            assertContains(success.value.chartImageUrls.first(), "pattern-1")
        }

    @Test
    fun `upload appends to existing chart image urls`() =
        runTest {
            val pattern = createTestPattern(chartImageUrls = listOf("user-1/pattern-1/old.jpg"))
            patternRepo.create(pattern)

            val result = useCase("pattern-1", validJpegBytes(512), "new.jpg")

            val success = assertIs<UseCaseResult.Success<Pattern>>(result)
            assertEquals(2, success.value.chartImageUrls.size)
        }

    @Test
    fun `upload with empty data returns validation error`() =
        runTest {
            patternRepo.create(createTestPattern())

            val result = useCase("pattern-1", ByteArray(0), "empty.jpg")

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(failure.error)
        }

    @Test
    fun `upload exceeding 2MB returns validation error`() =
        runTest {
            patternRepo.create(createTestPattern())
            val oversized = ByteArray(3 * 1024 * 1024) // 3MB

            val result = useCase("pattern-1", oversized, "big.jpg")

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(failure.error)
        }

    @Test
    fun `upload with unknown pattern returns not found error`() =
        runTest {
            val result = useCase("nonexistent", ByteArray(100), "chart.jpg")

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.NotFound>(failure.error)
        }

    @Test
    fun `upload without storage returns network error`() =
        runTest {
            val useCaseNoStorage = UploadChartImageUseCase(patternRepo, null, authRepo)
            patternRepo.create(createTestPattern())

            val result = useCaseNoStorage("pattern-1", validJpegBytes(100), "chart.jpg")

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Network>(failure.error)
        }

    @Test
    fun `upload with non-jpeg data returns validation error`() =
        runTest {
            patternRepo.create(createTestPattern())
            val pngBytes = ByteArray(100) // No JPEG magic bytes

            val result = useCase("pattern-1", pngBytes, "chart.jpg")

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(failure.error)
        }

    @Test
    fun `upload without authentication returns validation error`() =
        runTest {
            authRepo.setAuthState(AuthState.Unauthenticated)
            patternRepo.create(createTestPattern())

            val result = useCase("pattern-1", validJpegBytes(), "chart.jpg")

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(failure.error)
        }

    @Test
    fun `sanitizeFileName strips unsafe characters`() {
        assertEquals("chart_123.jpg", UploadChartImageUseCase.sanitizeFileName("chart_123.jpg"))
        assertEquals("____other___chart.jpg", UploadChartImageUseCase.sanitizeFileName("../../other/../chart.jpg"))
        assertEquals("file__name.jpg", UploadChartImageUseCase.sanitizeFileName("file<>name.jpg"))
    }

    @Test
    fun `sanitizeFileName normalizes double extensions`() {
        assertEquals("photo.jpg", UploadChartImageUseCase.sanitizeFileName("photo.jpg.html"))
        assertEquals("image.jpeg", UploadChartImageUseCase.sanitizeFileName("image.jpeg.exe"))
        assertEquals("chart.png", UploadChartImageUseCase.sanitizeFileName("chart.png.php"))
        // Single extension left intact
        assertEquals("normal.jpg", UploadChartImageUseCase.sanitizeFileName("normal.jpg"))
        // No recognized image extension strips to base name
        assertEquals("readme", UploadChartImageUseCase.sanitizeFileName("readme.txt.bak"))
    }

    @Test
    fun `isValidJpeg checks SOI and EOI markers`() {
        val validJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00, 0xFF.toByte(), 0xD9.toByte())
        val noEoi = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)
        val invalidData = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47) // PNG magic
        val tooShort = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
        // 4-byte degenerate case: SOI+EOI with no payload — rejected (size < 6)
        val degenerateFourBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())

        assertEquals(true, UploadChartImageUseCase.isValidJpeg(validJpeg))
        assertEquals(false, UploadChartImageUseCase.isValidJpeg(noEoi))
        assertEquals(false, UploadChartImageUseCase.isValidJpeg(invalidData))
        assertEquals(false, UploadChartImageUseCase.isValidJpeg(tooShort))
        assertEquals(false, UploadChartImageUseCase.isValidJpeg(degenerateFourBytes))
    }

    @Test
    fun `upload stores file in storage`() =
        runTest {
            patternRepo.create(createTestPattern())
            val data = validJpegBytes(256)

            useCase("pattern-1", data, "chart.jpg")

            assertEquals(1, storage.getUploadedFileCount())
        }
}
