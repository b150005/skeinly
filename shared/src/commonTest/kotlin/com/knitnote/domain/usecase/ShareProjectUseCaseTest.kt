package com.knitnote.domain.usecase

import com.knitnote.domain.LocalUser
import com.knitnote.domain.model.AuthState
import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import com.knitnote.domain.model.ShareLink
import com.knitnote.domain.model.SharePermission
import com.knitnote.domain.model.ShareStatus
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class ShareProjectUseCaseTest {

    private val testProject = Project(
        id = "p-1",
        ownerId = "test-user-id",
        patternId = LocalUser.DEFAULT_PATTERN_ID,
        title = "Test Scarf",
        status = ProjectStatus.IN_PROGRESS,
        currentRow = 10,
        totalRows = 100,
        startedAt = null,
        completedAt = null,
        createdAt = Instant.fromEpochMilliseconds(1000),
        updatedAt = Instant.fromEpochMilliseconds(2000),
    )

    private fun createUseCase(
        projectRepo: FakeProjectRepository = FakeProjectRepository(),
        patternRepo: FakePatternRepository = FakePatternRepository(),
        shareRepo: FakeShareRepository? = FakeShareRepository(),
        authRepo: FakeAuthRepository = FakeAuthRepository(),
    ) = ShareProjectUseCase(projectRepo, patternRepo, shareRepo, authRepo)

    @Test
    fun `returns failure when share repository is null`() = runTest {
        val useCase = createUseCase(shareRepo = null)
        val result = useCase("p-1")
        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
    }

    @Test
    fun `returns failure when not authenticated`() = runTest {
        val useCase = createUseCase()
        val result = useCase("p-1")
        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
    }

    @Test
    fun `returns failure when project not found`() = runTest {
        val authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("test-user-id", "test@test.com"))
        val useCase = createUseCase(authRepo = authRepo)

        val result = useCase("non-existent")
        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.NotFound>(result.error)
    }

    @Test
    fun `returns failure when sharing another user's project`() = runTest {
        val authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("different-user", "test@test.com"))
        val projectRepo = FakeProjectRepository()
        projectRepo.create(testProject) // owned by "test-user-id"
        val useCase = createUseCase(projectRepo = projectRepo, authRepo = authRepo)

        val result = useCase("p-1")
        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
    }

    @Test
    fun `creates pattern and share when project has no pattern`() = runTest {
        val authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("test-user-id", "test@test.com"))
        val projectRepo = FakeProjectRepository()
        projectRepo.create(testProject)
        val patternRepo = FakePatternRepository()
        val shareRepo = FakeShareRepository()
        val useCase = createUseCase(
            projectRepo = projectRepo,
            patternRepo = patternRepo,
            shareRepo = shareRepo,
            authRepo = authRepo,
        )

        val result = useCase("p-1")

        assertIs<UseCaseResult.Success<ShareLink>>(result)
        assertNotNull(result.value.shareToken)
        assertNotNull(result.value.patternId)
        assertNotEquals(LocalUser.DEFAULT_PATTERN_ID, result.value.patternId)

        // Verify pattern was created
        val pattern = patternRepo.getById(result.value.patternId)
        assertNotNull(pattern)
        assertEquals("Test Scarf", pattern.title)

        // Verify project was updated with pattern_id
        val updatedProject = projectRepo.getById("p-1")
        assertNotNull(updatedProject)
        assertEquals(result.value.patternId, updatedProject.patternId)
    }

    @Test
    fun `reuses existing pattern when project already has one`() = runTest {
        val authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("test-user-id", "test@test.com"))
        val projectWithPattern = testProject.copy(patternId = "existing-pattern-id")
        val projectRepo = FakeProjectRepository()
        projectRepo.create(projectWithPattern)
        val patternRepo = FakePatternRepository()
        val shareRepo = FakeShareRepository()
        val useCase = createUseCase(
            projectRepo = projectRepo,
            patternRepo = patternRepo,
            shareRepo = shareRepo,
            authRepo = authRepo,
        )

        val result = useCase("p-1")

        assertIs<UseCaseResult.Success<ShareLink>>(result)
        assertEquals("existing-pattern-id", result.value.patternId)

        // No new pattern should have been created
        assertEquals(0, patternRepo.getByOwnerId("test-user-id").size)
    }

    @Test
    fun `returns failure when sharing with self`() = runTest {
        val authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("test-user-id", "test@test.com"))
        val projectRepo = FakeProjectRepository()
        projectRepo.create(testProject)
        val useCase = createUseCase(projectRepo = projectRepo, authRepo = authRepo)

        val result = useCase("p-1", toUserId = "test-user-id")
        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
    }

    @Test
    fun `creates direct share with pending status and no token`() = runTest {
        val authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("test-user-id", "test@test.com"))
        val projectWithPattern = testProject.copy(patternId = "existing-pattern-id")
        val projectRepo = FakeProjectRepository()
        projectRepo.create(projectWithPattern)
        val shareRepo = FakeShareRepository()
        val useCase = createUseCase(
            projectRepo = projectRepo,
            shareRepo = shareRepo,
            authRepo = authRepo,
        )

        val result = useCase("p-1", toUserId = "recipient-id", permission = SharePermission.FORK)

        assertIs<UseCaseResult.Success<ShareLink>>(result)
        assertEquals(null, result.value.shareToken) // direct share has no token

        // Verify share in repo
        val shares = shareRepo.getReceivedByUserId("recipient-id")
        assertEquals(1, shares.size)
        val share = shares.first()
        assertEquals("recipient-id", share.toUserId)
        assertEquals(SharePermission.FORK, share.permission)
        assertEquals(ShareStatus.PENDING, share.status)
        assertEquals(null, share.shareToken)
    }

    @Test
    fun `link share has accepted status and token`() = runTest {
        val authRepo = FakeAuthRepository()
        authRepo.setAuthState(AuthState.Authenticated("test-user-id", "test@test.com"))
        val projectWithPattern = testProject.copy(patternId = "existing-pattern-id")
        val projectRepo = FakeProjectRepository()
        projectRepo.create(projectWithPattern)
        val shareRepo = FakeShareRepository()
        val useCase = createUseCase(
            projectRepo = projectRepo,
            shareRepo = shareRepo,
            authRepo = authRepo,
        )

        val result = useCase("p-1") // no toUserId = link share

        assertIs<UseCaseResult.Success<ShareLink>>(result)
        assertNotNull(result.value.shareToken)

        // Verify share has ACCEPTED status (link shares don't need acceptance)
        val shares = shareRepo.getByPatternId("existing-pattern-id")
        assertEquals(1, shares.size)
        assertEquals(ShareStatus.ACCEPTED, shares.first().status)
        assertEquals(null, shares.first().toUserId)
    }
}
