package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.ProjectStatus
import io.github.b150005.knitnote.domain.model.Share
import io.github.b150005.knitnote.domain.model.SharePermission
import io.github.b150005.knitnote.domain.model.ShareStatus
import io.github.b150005.knitnote.domain.model.Visibility
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.time.Instant

class ForkSharedPatternUseCaseTest {
    private val testPattern =
        Pattern(
            id = "pat-1",
            ownerId = "sharer-id",
            title = "Cable Knit Sweater",
            description = "A cozy cable knit pattern",
            difficulty = Difficulty.INTERMEDIATE,
            gauge = "20 stitches / 4 inches",
            yarnInfo = "DK weight wool",
            needleSize = "4mm",
            chartImageUrls = listOf("https://example.com/chart.png"),
            visibility = Visibility.SHARED,
            createdAt = Instant.fromEpochMilliseconds(1000),
            updatedAt = Instant.fromEpochMilliseconds(2000),
        )

    private val forkableShare =
        Share(
            id = "share-1",
            patternId = "pat-1",
            fromUserId = "sharer-id",
            toUserId = "user-1",
            permission = SharePermission.FORK,
            status = ShareStatus.ACCEPTED,
            shareToken = "token-1",
            sharedAt = Instant.fromEpochMilliseconds(1000),
        )

    private val viewOnlyShare =
        forkableShare.copy(
            id = "share-view",
            permission = SharePermission.VIEW,
        )

    private fun createUseCase(
        shareRepo: FakeShareRepository? = FakeShareRepository(),
        patternRepo: FakePatternRepository = FakePatternRepository(),
        projectRepo: FakeProjectRepository = FakeProjectRepository(),
        authRepo: FakeAuthRepository = FakeAuthRepository(),
    ) = ForkSharedPatternUseCase(shareRepo, patternRepo, projectRepo, authRepo)

    @Test
    fun `returns failure when share repository is null`() =
        runTest {
            val useCase = createUseCase(shareRepo = null)
            val result = useCase("share-1")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `returns failure when not authenticated`() =
        runTest {
            val useCase = createUseCase()
            val result = useCase("share-1")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `returns failure when share not found`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val useCase = createUseCase(authRepo = authRepo)

            val result = useCase("non-existent")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.NotFound>(result.error)
        }

    @Test
    fun `returns failure when permission is VIEW`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val shareRepo = FakeShareRepository()
            shareRepo.addShare(viewOnlyShare)
            val useCase = createUseCase(shareRepo = shareRepo, authRepo = authRepo)

            val result = useCase("share-view")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `returns failure when share belongs to another user`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("different-user", "test@test.com"))
            val shareRepo = FakeShareRepository()
            shareRepo.addShare(forkableShare) // toUserId = "user-1"
            val useCase = createUseCase(shareRepo = shareRepo, authRepo = authRepo)

            val result = useCase("share-1")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `returns failure when share is declined`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val shareRepo = FakeShareRepository()
            shareRepo.addShare(forkableShare.copy(status = ShareStatus.DECLINED))
            val useCase = createUseCase(shareRepo = shareRepo, authRepo = authRepo)

            val result = useCase("share-1")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `allows fork when toUserId is null for link share`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("any-user", "test@test.com"))
            val shareRepo = FakeShareRepository()
            shareRepo.addShare(forkableShare.copy(toUserId = null))
            val patternRepo = FakePatternRepository()
            patternRepo.create(testPattern)
            val projectRepo = FakeProjectRepository()
            val useCase =
                createUseCase(
                    shareRepo = shareRepo,
                    patternRepo = patternRepo,
                    projectRepo = projectRepo,
                    authRepo = authRepo,
                )

            val result = useCase("share-1")
            assertIs<UseCaseResult.Success<ForkedProject>>(result)
        }

    @Test
    fun `returns failure when source pattern not found`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val shareRepo = FakeShareRepository()
            shareRepo.addShare(forkableShare)
            val useCase = createUseCase(shareRepo = shareRepo, authRepo = authRepo)

            val result = useCase("share-1")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.NotFound>(result.error)
        }

    @Test
    fun `successfully forks pattern and creates project`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val shareRepo = FakeShareRepository()
            shareRepo.addShare(forkableShare)
            val patternRepo = FakePatternRepository()
            patternRepo.create(testPattern)
            val projectRepo = FakeProjectRepository()
            val useCase =
                createUseCase(
                    shareRepo = shareRepo,
                    patternRepo = patternRepo,
                    projectRepo = projectRepo,
                    authRepo = authRepo,
                )

            val result = useCase("share-1")

            assertIs<UseCaseResult.Success<ForkedProject>>(result)
            val forked = result.value

            // Forked pattern has new ID and correct owner
            assertNotEquals("pat-1", forked.pattern.id)
            assertEquals("user-1", forked.pattern.ownerId)
            assertEquals(Visibility.PRIVATE, forked.pattern.visibility)

            // Pattern content is preserved
            assertEquals("Cable Knit Sweater", forked.pattern.title)
            assertEquals("A cozy cable knit pattern", forked.pattern.description)
            assertEquals(Difficulty.INTERMEDIATE, forked.pattern.difficulty)
            assertEquals("20 stitches / 4 inches", forked.pattern.gauge)
            assertEquals("DK weight wool", forked.pattern.yarnInfo)
            assertEquals("4mm", forked.pattern.needleSize)
            assertEquals(listOf("https://example.com/chart.png"), forked.pattern.chartImageUrls)
        }

    @Test
    fun `forked project links to forked pattern not original`() =
        runTest {
            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val shareRepo = FakeShareRepository()
            shareRepo.addShare(forkableShare)
            val patternRepo = FakePatternRepository()
            patternRepo.create(testPattern)
            val projectRepo = FakeProjectRepository()
            val useCase =
                createUseCase(
                    shareRepo = shareRepo,
                    patternRepo = patternRepo,
                    projectRepo = projectRepo,
                    authRepo = authRepo,
                )

            val result = useCase("share-1")

            assertIs<UseCaseResult.Success<ForkedProject>>(result)
            val forked = result.value

            // Project links to forked pattern, not original
            assertEquals(forked.pattern.id, forked.project.patternId)
            assertNotEquals("pat-1", forked.project.patternId)

            // Project has correct owner and initial state
            assertEquals("user-1", forked.project.ownerId)
            assertEquals("Cable Knit Sweater", forked.project.title)
            assertEquals(ProjectStatus.NOT_STARTED, forked.project.status)
            assertEquals(0, forked.project.currentRow)
        }
}
