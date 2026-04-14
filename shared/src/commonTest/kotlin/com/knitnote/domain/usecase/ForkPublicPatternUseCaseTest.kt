package com.knitnote.domain.usecase

import com.knitnote.domain.model.AuthState
import com.knitnote.domain.model.Difficulty
import com.knitnote.domain.model.Pattern
import com.knitnote.domain.model.ProjectStatus
import com.knitnote.domain.model.Visibility
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Instant

class ForkPublicPatternUseCaseTest {
    private val publicPattern =
        Pattern(
            id = "pub-1",
            ownerId = "other-user",
            title = "Cable Knit Sweater",
            description = "A cozy cable knit pattern",
            difficulty = Difficulty.INTERMEDIATE,
            gauge = "20 stitches / 4 inches",
            yarnInfo = "DK weight wool",
            needleSize = "4mm",
            chartImageUrls = listOf("https://example.com/chart.png"),
            visibility = Visibility.PUBLIC,
            createdAt = Instant.fromEpochMilliseconds(1000),
            updatedAt = Instant.fromEpochMilliseconds(2000),
        )

    private val privatePattern = publicPattern.copy(id = "priv-1", visibility = Visibility.PRIVATE)

    private fun createUseCase(
        patternRepo: FakePatternRepository = FakePatternRepository(),
        projectRepo: FakeProjectRepository = FakeProjectRepository(),
        authRepo: FakeAuthRepository = FakeAuthRepository(),
    ) = ForkPublicPatternUseCase(patternRepo, projectRepo, authRepo)

    @Test
    fun `returns Validation failure when not authenticated`() =
        runTest {
            val useCase = createUseCase()

            val result = useCase("pub-1")

            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `returns NotFound failure when pattern does not exist`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val useCase = createUseCase(authRepo = authRepo)

            val result = useCase("nonexistent")

            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.NotFound>(result.error)
        }

    @Test
    fun `returns Validation failure when pattern is not public`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val patternRepo = FakePatternRepository()
            patternRepo.create(privatePattern)
            val useCase = createUseCase(patternRepo = patternRepo, authRepo = authRepo)

            val result = useCase("priv-1")

            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `returns Success with forked pattern and project`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val patternRepo = FakePatternRepository()
            patternRepo.create(publicPattern)
            val projectRepo = FakeProjectRepository()
            val useCase = createUseCase(patternRepo = patternRepo, projectRepo = projectRepo, authRepo = authRepo)

            val result = useCase("pub-1")

            assertIs<UseCaseResult.Success<ForkedProject>>(result)
            val forked = result.value

            // Forked pattern has new ID and belongs to current user
            assertNotEquals("pub-1", forked.pattern.id)
            assertEquals("user-1", forked.pattern.ownerId)
            assertEquals(Visibility.PRIVATE, forked.pattern.visibility)

            // Content is preserved
            assertEquals("Cable Knit Sweater", forked.pattern.title)
            assertEquals("A cozy cable knit pattern", forked.pattern.description)
            assertEquals(Difficulty.INTERMEDIATE, forked.pattern.difficulty)
            assertEquals("20 stitches / 4 inches", forked.pattern.gauge)
            assertEquals(listOf("https://example.com/chart.png"), forked.pattern.chartImageUrls)

            // Project is created linked to forked pattern
            assertEquals(forked.pattern.id, forked.project.patternId)
            assertEquals("user-1", forked.project.ownerId)
            assertEquals(ProjectStatus.NOT_STARTED, forked.project.status)
            assertEquals(0, forked.project.currentRow)
        }

    @Test
    fun `forked pattern has different timestamps than source`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val patternRepo = FakePatternRepository()
            patternRepo.create(publicPattern)
            val useCase = createUseCase(patternRepo = patternRepo, authRepo = authRepo)

            val result = useCase("pub-1")

            assertIs<UseCaseResult.Success<ForkedProject>>(result)
            assertNotEquals(publicPattern.createdAt, result.value.pattern.createdAt)
        }
}
