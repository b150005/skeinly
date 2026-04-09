package com.knitnote.domain.usecase

import com.knitnote.domain.model.Pattern
import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import com.knitnote.domain.model.Share
import com.knitnote.domain.model.SharePermission
import com.knitnote.domain.model.ShareStatus
import com.knitnote.domain.model.Visibility
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ResolveShareTokenUseCaseTest {

    private val testPattern = Pattern(
        id = "pat-1",
        ownerId = "user-1",
        title = "Shared Scarf Pattern",
        description = null,
        difficulty = null,
        gauge = null,
        yarnInfo = null,
        needleSize = null,
        chartImageUrls = emptyList(),
        visibility = Visibility.SHARED,
        createdAt = Instant.fromEpochMilliseconds(1000),
        updatedAt = Instant.fromEpochMilliseconds(2000),
    )

    private val testShare = Share(
        id = "s-1",
        patternId = "pat-1",
        fromUserId = "user-1",
        toUserId = null,
        permission = SharePermission.VIEW,
        status = ShareStatus.ACCEPTED,
        shareToken = "token-abc",
        sharedAt = Instant.fromEpochMilliseconds(3000),
    )

    private val directShare = Share(
        id = "s-direct",
        patternId = "pat-1",
        fromUserId = "user-1",
        toUserId = "recipient-id",
        permission = SharePermission.FORK,
        status = ShareStatus.PENDING,
        shareToken = null,
        sharedAt = Instant.fromEpochMilliseconds(3000),
    )

    private val testProject = Project(
        id = "p-1",
        ownerId = "user-1",
        patternId = "pat-1",
        title = "My Scarf",
        status = ProjectStatus.IN_PROGRESS,
        currentRow = 10,
        totalRows = 100,
        startedAt = null,
        completedAt = null,
        createdAt = Instant.fromEpochMilliseconds(1000),
        updatedAt = Instant.fromEpochMilliseconds(2000),
    )

    @Test
    fun `returns failure when share repository is null`() = runTest {
        val useCase = ResolveShareTokenUseCase(null, FakePatternRepository(), FakeProjectRepository())
        val result = useCase(token = "token-abc")
        assertIs<UseCaseResult.Failure>(result)
    }

    @Test
    fun `returns failure when neither token nor shareId provided`() = runTest {
        val useCase = ResolveShareTokenUseCase(FakeShareRepository(), FakePatternRepository(), FakeProjectRepository())
        val result = useCase()
        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
    }

    @Test
    fun `returns failure for blank token`() = runTest {
        val useCase = ResolveShareTokenUseCase(FakeShareRepository(), FakePatternRepository(), FakeProjectRepository())
        val result = useCase(token = "  ")
        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
    }

    @Test
    fun `returns failure when token not found`() = runTest {
        val useCase = ResolveShareTokenUseCase(FakeShareRepository(), FakePatternRepository(), FakeProjectRepository())
        val result = useCase(token = "non-existent-token")
        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.NotFound>(result.error)
    }

    @Test
    fun `returns failure when pattern not found`() = runTest {
        val shareRepo = FakeShareRepository()
        shareRepo.addShare(testShare)
        val useCase = ResolveShareTokenUseCase(shareRepo, FakePatternRepository(), FakeProjectRepository())

        val result = useCase(token = "token-abc")
        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.NotFound>(result.error)
    }

    @Test
    fun `resolves token to pattern and associated projects`() = runTest {
        val shareRepo = FakeShareRepository()
        shareRepo.addShare(testShare)
        val patternRepo = FakePatternRepository()
        patternRepo.create(testPattern)
        val projectRepo = FakeProjectRepository()
        projectRepo.create(testProject)
        val useCase = ResolveShareTokenUseCase(shareRepo, patternRepo, projectRepo)

        val result = useCase(token = "token-abc")

        assertIs<UseCaseResult.Success<SharedContent>>(result)
        assertEquals("pat-1", result.value.pattern.id)
        assertEquals("Shared Scarf Pattern", result.value.pattern.title)
        assertEquals(1, result.value.projects.size)
        assertEquals("p-1", result.value.projects[0].id)
    }

    @Test
    fun `returns empty projects when none linked to pattern`() = runTest {
        val shareRepo = FakeShareRepository()
        shareRepo.addShare(testShare)
        val patternRepo = FakePatternRepository()
        patternRepo.create(testPattern)
        val useCase = ResolveShareTokenUseCase(shareRepo, patternRepo, FakeProjectRepository())

        val result = useCase(token = "token-abc")

        assertIs<UseCaseResult.Success<SharedContent>>(result)
        assertEquals(0, result.value.projects.size)
    }

    @Test
    fun `resolves shareId to pattern and projects`() = runTest {
        val shareRepo = FakeShareRepository()
        shareRepo.addShare(directShare)
        val patternRepo = FakePatternRepository()
        patternRepo.create(testPattern)
        val projectRepo = FakeProjectRepository()
        projectRepo.create(testProject)
        val useCase = ResolveShareTokenUseCase(shareRepo, patternRepo, projectRepo)

        val result = useCase(shareId = "s-direct")

        assertIs<UseCaseResult.Success<SharedContent>>(result)
        assertEquals("pat-1", result.value.pattern.id)
        assertEquals(SharePermission.FORK, result.value.share.permission)
        assertEquals("recipient-id", result.value.share.toUserId)
    }

    @Test
    fun `returns failure when shareId not found`() = runTest {
        val useCase = ResolveShareTokenUseCase(FakeShareRepository(), FakePatternRepository(), FakeProjectRepository())
        val result = useCase(shareId = "non-existent")
        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.NotFound>(result.error)
    }
}
