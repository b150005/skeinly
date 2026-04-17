package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Visibility
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Clock

class UpdatePatternUseCaseTest {
    private val patternRepository = FakePatternRepository()
    private val authRepository = FakeAuthRepository()
    private val useCase = UpdatePatternUseCase(patternRepository)

    private fun createTestPattern(): Pattern {
        val now = Clock.System.now()
        return Pattern(
            id = "p1",
            ownerId = "user-1",
            title = "Original Title",
            description = "Original description",
            difficulty = Difficulty.BEGINNER,
            gauge = "20 sts",
            yarnInfo = "Worsted",
            needleSize = "US 7",
            chartImageUrls = listOf("img1.jpg"),
            visibility = Visibility.PRIVATE,
            createdAt = now,
            updatedAt = now,
        )
    }

    @Test
    fun `updates all fields successfully`() =
        runTest {
            val original = createTestPattern()
            patternRepository.create(original)

            val result =
                useCase(
                    patternId = "p1",
                    title = "Updated Title",
                    description = "Updated desc",
                    difficulty = Difficulty.ADVANCED,
                    gauge = "24 sts",
                    yarnInfo = "DK weight",
                    needleSize = "US 5",
                    visibility = Visibility.SHARED,
                )

            assertIs<UseCaseResult.Success<Pattern>>(result)
            val updated = result.value
            assertEquals("Updated Title", updated.title)
            assertEquals("Updated desc", updated.description)
            assertEquals(Difficulty.ADVANCED, updated.difficulty)
            assertEquals("24 sts", updated.gauge)
            assertEquals("DK weight", updated.yarnInfo)
            assertEquals("US 5", updated.needleSize)
            assertEquals(Visibility.SHARED, updated.visibility)
        }

    @Test
    fun `blank title returns Validation error`() =
        runTest {
            patternRepository.create(createTestPattern())

            val result = useCase(patternId = "p1", title = "  ")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `non-existent pattern returns NotFound error`() =
        runTest {
            val result = useCase(patternId = "non-existent", title = "Title")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.NotFound>(result.error)
        }

    @Test
    fun `preserves chartImageUrls and ownerId`() =
        runTest {
            val original = createTestPattern()
            patternRepository.create(original)

            val result =
                useCase(
                    patternId = "p1",
                    title = "New Title",
                    visibility = Visibility.PRIVATE,
                )

            assertIs<UseCaseResult.Success<Pattern>>(result)
            assertEquals(listOf("img1.jpg"), result.value.chartImageUrls)
            assertEquals("user-1", result.value.ownerId)
            assertEquals(original.createdAt, result.value.createdAt)
        }

    @Test
    fun `updates updatedAt timestamp`() =
        runTest {
            val original = createTestPattern()
            patternRepository.create(original)

            val result = useCase(patternId = "p1", title = "New Title")

            assertIs<UseCaseResult.Success<Pattern>>(result)
            assertNotEquals(original.updatedAt, result.value.updatedAt)
        }
}
