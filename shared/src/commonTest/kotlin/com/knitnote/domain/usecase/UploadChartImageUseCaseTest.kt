package com.knitnote.domain.usecase

import com.knitnote.data.remote.FakeRemoteStorageDataSource
import com.knitnote.domain.model.AuthState
import com.knitnote.domain.model.Difficulty
import com.knitnote.domain.model.Pattern
import com.knitnote.domain.model.Visibility
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

            val result = useCase("pattern-1", ByteArray(1024), "chart.jpg")

            val success = assertIs<UseCaseResult.Success<Pattern>>(result)
            assertEquals(1, success.value.chartImageUrls.size)
            assertContains(success.value.chartImageUrls.first(), "pattern-1")
        }

    @Test
    fun `upload appends to existing chart image urls`() =
        runTest {
            val pattern = createTestPattern(chartImageUrls = listOf("user-1/pattern-1/old.jpg"))
            patternRepo.create(pattern)

            val result = useCase("pattern-1", ByteArray(512), "new.jpg")

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

            val result = useCaseNoStorage("pattern-1", ByteArray(100), "chart.jpg")

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Network>(failure.error)
        }

    @Test
    fun `upload stores file in storage`() =
        runTest {
            patternRepo.create(createTestPattern())
            val data = ByteArray(256) { it.toByte() }

            useCase("pattern-1", data, "chart.jpg")

            assertEquals(1, storage.getUploadedFileCount())
        }
}
