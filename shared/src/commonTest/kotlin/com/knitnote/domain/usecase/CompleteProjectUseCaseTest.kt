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
import kotlin.test.assertNotNull

class CompleteProjectUseCaseTest {

    private lateinit var repository: FakeProjectRepository
    private lateinit var useCase: CompleteProjectUseCase

    @BeforeTest
    fun setUp() {
        repository = FakeProjectRepository()
        useCase = CompleteProjectUseCase(repository)
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
        title = "Test",
        status = status,
        currentRow = currentRow,
        totalRows = totalRows,
        startedAt = Clock.System.now(),
        completedAt = null,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    @Test
    fun `complete IN_PROGRESS project sets status to COMPLETED`() = runTest {
        repository.create(createProject())

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assertEquals(ProjectStatus.COMPLETED, result.value.status)
        assertNotNull(result.value.completedAt)
    }

    @Test
    fun `complete NOT_STARTED project sets status to COMPLETED`() = runTest {
        repository.create(createProject(currentRow = 0, status = ProjectStatus.NOT_STARTED))

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assertEquals(ProjectStatus.COMPLETED, result.value.status)
        assertNotNull(result.value.completedAt)
    }

    @Test
    fun `complete already COMPLETED project is no-op`() = runTest {
        val completedAt = Clock.System.now()
        val project = createProject(status = ProjectStatus.COMPLETED).copy(completedAt = completedAt)
        repository.create(project)

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assertEquals(ProjectStatus.COMPLETED, result.value.status)
        assertEquals(completedAt, result.value.completedAt)
    }

    @Test
    fun `complete preserves currentRow`() = runTest {
        repository.create(createProject(currentRow = 42))

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assertEquals(42, result.value.currentRow)
    }

    @Test
    fun `complete updates updatedAt`() = runTest {
        repository.create(createProject())
        val before = Clock.System.now()

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assert(result.value.updatedAt >= before)
    }

    @Test
    fun `complete returns NotFound for missing project`() = runTest {
        val result = useCase("non-existent")

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.NotFound>(result.error)
    }
}
