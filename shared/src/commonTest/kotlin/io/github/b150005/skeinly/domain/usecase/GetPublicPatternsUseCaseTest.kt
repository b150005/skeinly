package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.data.remote.FakePublicPatternDataSource
import io.github.b150005.skeinly.data.remote.PublicPatternsResult
import io.github.b150005.skeinly.domain.model.Difficulty
import io.github.b150005.skeinly.domain.model.Pattern
import io.github.b150005.skeinly.domain.model.Visibility
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
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
            assertEquals(UseCaseError.RequiresConnectivity, result.error)
        }

    @Test
    fun `returns Success with patterns from remote`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            dataSource.addPattern(publicPattern)
            val useCase = GetPublicPatternsUseCase(dataSource)

            val result = useCase()

            assertIs<UseCaseResult.Success<PublicPatternsResult>>(result)
            assertEquals(1, result.value.patterns.size)
            assertEquals(
                "pub-1",
                result.value.patterns
                    .first()
                    .id,
            )
        }

    @Test
    fun `returns Success with filtered patterns for search query`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            dataSource.addPattern(publicPattern)
            dataSource.addPattern(publicPattern.copy(id = "pub-2", title = "Ribbed Hat"))
            val useCase = GetPublicPatternsUseCase(dataSource)

            val result = useCase("cable")

            assertIs<UseCaseResult.Success<PublicPatternsResult>>(result)
            assertEquals(1, result.value.patterns.size)
            assertEquals(
                "Cable Knit Scarf",
                result.value.patterns
                    .first()
                    .title,
            )
        }

    @Test
    fun `returns empty list when no patterns match`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            val useCase = GetPublicPatternsUseCase(dataSource)

            val result = useCase()

            assertIs<UseCaseResult.Success<PublicPatternsResult>>(result)
            assertEquals(0, result.value.patterns.size)
            assertTrue(result.value.patternsWithCharts.isEmpty())
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

    // Phase 36.4 (ADR-012 §5): the companion set names which of the returned
    // pattern ids have a chart_documents row. Discovery's PatternCard checks
    // membership to decide whether to render the live thumbnail; without this
    // round-trip test the UseCase could regress to dropping the companion set.
    @Test
    fun `populates patternsWithCharts companion set when chartsOnly is false`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            dataSource.addPattern(publicPattern)
            dataSource.addPattern(publicPattern.copy(id = "pub-2", title = "Ribbed Hat"))
            dataSource.markHasChart("pub-1")
            val useCase = GetPublicPatternsUseCase(dataSource)

            val result = useCase()

            assertIs<UseCaseResult.Success<PublicPatternsResult>>(result)
            assertEquals(2, result.value.patterns.size)
            assertEquals(setOf("pub-1"), result.value.patternsWithCharts)
        }

    // Phase 36.4 (ADR-012 §4): when chartsOnly=true the result list is filtered
    // server-side to patterns whose chart_documents row exists.
    @Test
    fun `chartsOnly true filters list to chartful patterns`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            dataSource.addPattern(publicPattern)
            dataSource.addPattern(publicPattern.copy(id = "pub-2", title = "Ribbed Hat"))
            dataSource.markHasChart("pub-1")
            val useCase = GetPublicPatternsUseCase(dataSource)

            val result = useCase(searchQuery = "", chartsOnly = true)

            assertIs<UseCaseResult.Success<PublicPatternsResult>>(result)
            assertEquals(1, result.value.patterns.size)
            assertEquals(
                "pub-1",
                result.value.patterns
                    .first()
                    .id,
            )
            assertEquals(setOf("pub-1"), result.value.patternsWithCharts)
        }
}
