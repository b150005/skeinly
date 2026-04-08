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
import kotlin.test.assertNull

class ReopenProjectUseCaseTest {

    private lateinit var repository: FakeProjectRepository
    private lateinit var useCase: ReopenProjectUseCase

    @BeforeTest
    fun setUp() {
        repository = FakeProjectRepository()
        useCase = ReopenProjectUseCase(repository)
    }

    private fun createProject(
        id: String = "test-project",
        currentRow: Int = 50,
        totalRows: Int? = 100,
        status: ProjectStatus = ProjectStatus.COMPLETED,
    ): Project = Project(
        id = id,
        ownerId = LocalUser.ID,
        patternId = LocalUser.DEFAULT_PATTERN_ID,
        title = "Test",
        status = status,
        currentRow = currentRow,
        totalRows = totalRows,
        startedAt = Clock.System.now(),
        completedAt = Clock.System.now(),
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    @Test
    fun `reopen COMPLETED project with rows sets IN_PROGRESS`() = runTest {
        repository.create(createProject(currentRow = 50))

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assertEquals(ProjectStatus.IN_PROGRESS, result.value.status)
        assertNull(result.value.completedAt)
    }

    @Test
    fun `reopen COMPLETED project with zero rows sets NOT_STARTED`() = runTest {
        repository.create(createProject(currentRow = 0))

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assertEquals(ProjectStatus.NOT_STARTED, result.value.status)
        assertNull(result.value.completedAt)
    }

    @Test
    fun `reopen IN_PROGRESS project is no-op`() = runTest {
        val project = createProject(status = ProjectStatus.IN_PROGRESS).copy(completedAt = null)
        repository.create(project)

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assertEquals(ProjectStatus.IN_PROGRESS, result.value.status)
    }

    @Test
    fun `reopen NOT_STARTED project is no-op`() = runTest {
        val project = createProject(status = ProjectStatus.NOT_STARTED, currentRow = 0)
            .copy(completedAt = null)
        repository.create(project)

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assertEquals(ProjectStatus.NOT_STARTED, result.value.status)
    }

    @Test
    fun `reopen preserves currentRow`() = runTest {
        repository.create(createProject(currentRow = 75))

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assertEquals(75, result.value.currentRow)
    }

    @Test
    fun `reopen updates updatedAt`() = runTest {
        repository.create(createProject())
        val before = Clock.System.now()

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assert(result.value.updatedAt >= before)
    }

    @Test
    fun `reopen returns NotFound for missing project`() = runTest {
        val result = useCase("non-existent")

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.NotFound>(result.error)
    }
}
