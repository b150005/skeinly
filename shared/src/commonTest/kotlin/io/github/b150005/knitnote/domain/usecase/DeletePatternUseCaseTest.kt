package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Visibility
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock

class DeletePatternUseCaseTest {
    private val patternRepository = FakePatternRepository()
    private val useCase = DeletePatternUseCase(patternRepository)

    private fun createTestPattern(): Pattern {
        val now = Clock.System.now()
        return Pattern(
            id = "p1",
            ownerId = "user-1",
            title = "Test Pattern",
            description = null,
            difficulty = Difficulty.BEGINNER,
            gauge = null,
            yarnInfo = null,
            needleSize = null,
            chartImageUrls = emptyList(),
            visibility = Visibility.PRIVATE,
            createdAt = now,
            updatedAt = now,
        )
    }

    @Test
    fun `deletes pattern successfully`() =
        runTest {
            patternRepository.create(createTestPattern())

            val result = useCase("p1")
            assertIs<UseCaseResult.Success<Unit>>(result)
        }

    @Test
    fun `pattern no longer retrievable after delete`() =
        runTest {
            patternRepository.create(createTestPattern())
            useCase("p1")

            assertNull(patternRepository.getById("p1"))
        }
}
