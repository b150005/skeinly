package com.knitnote.domain.usecase

import com.knitnote.domain.LocalUser
import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import kotlinx.coroutines.test.runTest
import kotlin.time.Clock
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UpdateProjectUseCaseTest {

    private lateinit var repository: FakeProjectRepository
    private lateinit var useCase: UpdateProjectUseCase

    @BeforeTest
    fun setUp() {
        repository = FakeProjectRepository()
        useCase = UpdateProjectUseCase(repository)
    }

    private fun createProject(
        id: String = "test-project",
        currentRow: Int = 5,
        totalRows: Int? = 100,
        status: ProjectStatus = ProjectStatus.IN_PROGRESS,
    ): Project = Project(
        id = id,
        ownerId = LocalUser.ID,
        patternId = LocalUser.DEFAULT_PATTERN_ID,
        title = "Original Title",
        status = status,
        currentRow = currentRow,
        totalRows = totalRows,
        startedAt = Clock.System.now(),
        completedAt = null,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    @Test
    fun `updates title successfully`() = runTest {
        repository.create(createProject())

        val result = assertIs<UseCaseResult.Success<Project>>(
            useCase(projectId = "test-project", title = "New Title", totalRows = 100),
        )

        assertEquals("New Title", result.value.title)
    }

    @Test
    fun `updates totalRows successfully`() = runTest {
        repository.create(createProject())

        val result = assertIs<UseCaseResult.Success<Project>>(
            useCase(projectId = "test-project", title = "Original Title", totalRows = 200),
        )

        assertEquals(200, result.value.totalRows)
    }

    @Test
    fun `returns NotFound for missing project`() = runTest {
        val result = useCase(projectId = "non-existent", title = "Title", totalRows = 100)

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.NotFound>(result.error)
    }

    @Test
    fun `returns Validation error for blank title`() = runTest {
        repository.create(createProject())

        val result = useCase(projectId = "test-project", title = "  ", totalRows = 100)

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
    }

    @Test
    fun `returns Validation error for empty title`() = runTest {
        repository.create(createProject())

        val result = useCase(projectId = "test-project", title = "", totalRows = 100)

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
    }

    @Test
    fun `auto-completes when currentRow reaches new totalRows`() = runTest {
        repository.create(createProject(currentRow = 50, totalRows = 100))

        val result = assertIs<UseCaseResult.Success<Project>>(
            useCase(projectId = "test-project", title = "Original Title", totalRows = 50),
        )

        assertEquals(ProjectStatus.COMPLETED, result.value.status)
    }

    @Test
    fun `un-completes when totalRows increased above currentRow`() = runTest {
        val project = createProject(currentRow = 100, totalRows = 100, status = ProjectStatus.COMPLETED)
            .copy(completedAt = Clock.System.now())
        repository.create(project)

        val result = assertIs<UseCaseResult.Success<Project>>(
            useCase(projectId = "test-project", title = "Original Title", totalRows = 200),
        )

        assertEquals(ProjectStatus.IN_PROGRESS, result.value.status)
    }

    @Test
    fun `updates updatedAt timestamp`() = runTest {
        repository.create(createProject())
        val before = Clock.System.now()

        val result = assertIs<UseCaseResult.Success<Project>>(
            useCase(projectId = "test-project", title = "New Title", totalRows = 100),
        )

        assertTrue(result.value.updatedAt >= before)
    }
}
