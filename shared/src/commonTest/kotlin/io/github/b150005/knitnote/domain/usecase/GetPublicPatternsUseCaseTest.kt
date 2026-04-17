package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.data.remote.FakePublicPatternDataSource
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Visibility
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

class GetPublicPatternsUseCaseTest {
    private val publicPattern =
        Pattern(
            id = "pub-1",
            ownerId = "other-user",
            title = "Cable Knit Scarf",
            description = "A warm cable knit scarf",
            difficulty = Difficulty.BEGINNER,
            gauge = null,
            yarnInfo = null,
            needleSize = null,
            chartImageUrls = emptyList(),
            visibility = Visibility.PUBLIC,
            createdAt = Instant.fromEpochMilliseconds(1000),
            updatedAt = Instant.fromEpochMilliseconds(2000),
        )

    @Test
    fun `returns Validation failure when data source is null`() =
        runTest {
            val useCase = GetPublicPatternsUseCase(null)

            val result = useCase()

            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `returns Success with patterns from remote`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            dataSource.addPattern(publicPattern)
            val useCase = GetPublicPatternsUseCase(dataSource)

            val result = useCase()

            assertIs<UseCaseResult.Success<List<Pattern>>>(result)
            assertEquals(1, result.value.size)
            assertEquals("pub-1", result.value.first().id)
        }

    @Test
    fun `returns Success with filtered patterns for search query`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            dataSource.addPattern(publicPattern)
            dataSource.addPattern(publicPattern.copy(id = "pub-2", title = "Ribbed Hat"))
            val useCase = GetPublicPatternsUseCase(dataSource)

            val result = useCase("cable")

            assertIs<UseCaseResult.Success<List<Pattern>>>(result)
            assertEquals(1, result.value.size)
            assertEquals("Cable Knit Scarf", result.value.first().title)
        }

    @Test
    fun `returns empty list when no patterns match`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            val useCase = GetPublicPatternsUseCase(dataSource)

            val result = useCase()

            assertIs<UseCaseResult.Success<List<Pattern>>>(result)
            assertEquals(0, result.value.size)
        }

    @Test
    fun `returns Network failure on remote exception`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            dataSource.shouldFail = true
            val useCase = GetPublicPatternsUseCase(dataSource)

            val result = useCase()

            assertIs<UseCaseResult.Failure>(result)
        }
}
