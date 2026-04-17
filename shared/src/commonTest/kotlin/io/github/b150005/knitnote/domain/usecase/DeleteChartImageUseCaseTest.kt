package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.data.remote.FakeRemoteStorageDataSource
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Visibility
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock

class DeleteChartImageUseCaseTest {
    private lateinit var patternRepo: FakePatternRepository
    private lateinit var storage: FakeRemoteStorageDataSource
    private lateinit var useCase: DeleteChartImageUseCase

    @BeforeTest
    fun setUp() {
        patternRepo = FakePatternRepository()
        storage = FakeRemoteStorageDataSource()
        useCase = DeleteChartImageUseCase(patternRepo, storage)
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
    fun `delete existing image removes from pattern`() =
        runTest {
            val imagePath = "user-1/pattern-1/chart.jpg"
            val pattern = createTestPattern(chartImageUrls = listOf(imagePath, "user-1/pattern-1/other.jpg"))
            patternRepo.create(pattern)

            val result = useCase("pattern-1", imagePath)

            val success = assertIs<UseCaseResult.Success<Pattern>>(result)
            assertEquals(1, success.value.chartImageUrls.size)
            assertEquals("user-1/pattern-1/other.jpg", success.value.chartImageUrls.first())
        }

    @Test
    fun `delete nonexistent path returns not found error`() =
        runTest {
            val pattern = createTestPattern(chartImageUrls = listOf("user-1/pattern-1/chart.jpg"))
            patternRepo.create(pattern)

            val result = useCase("pattern-1", "nonexistent/path.jpg")

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.NotFound>(failure.error)
        }

    @Test
    fun `delete with unknown pattern returns not found error`() =
        runTest {
            val result = useCase("nonexistent", "some/path.jpg")

            val failure = assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.NotFound>(failure.error)
        }

    @Test
    fun `delete last image results in empty list`() =
        runTest {
            val imagePath = "user-1/pattern-1/chart.jpg"
            val pattern = createTestPattern(chartImageUrls = listOf(imagePath))
            patternRepo.create(pattern)

            val result = useCase("pattern-1", imagePath)

            val success = assertIs<UseCaseResult.Success<Pattern>>(result)
            assertEquals(0, success.value.chartImageUrls.size)
        }

    @Test
    fun `delete without storage still removes from pattern`() =
        runTest {
            val useCaseNoStorage = DeleteChartImageUseCase(patternRepo, null)
            val imagePath = "user-1/pattern-1/chart.jpg"
            val pattern = createTestPattern(chartImageUrls = listOf(imagePath))
            patternRepo.create(pattern)

            val result = useCaseNoStorage("pattern-1", imagePath)

            val success = assertIs<UseCaseResult.Success<Pattern>>(result)
            assertEquals(0, success.value.chartImageUrls.size)
        }
}
