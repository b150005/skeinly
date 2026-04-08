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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IncrementRowUseCaseTest {

    private lateinit var repository: FakeProjectRepository
    private lateinit var useCase: IncrementRowUseCase

    @BeforeTest
    fun setUp() {
        repository = FakeProjectRepository()
        useCase = IncrementRowUseCase(repository)
    }

    private fun createProject(
        id: String = "test-project",
        currentRow: Int = 0,
        totalRows: Int? = 100,
        status: ProjectStatus = ProjectStatus.NOT_STARTED,
    ): Project = Project(
        id = id,
        ownerId = LocalUser.ID,
        patternId = LocalUser.DEFAULT_PATTERN_ID,
        title = "Test",
        status = status,
        currentRow = currentRow,
        totalRows = totalRows,
        startedAt = null,
        completedAt = null,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    @Test
    fun `increment from 0 changes status to IN_PROGRESS`() = runTest {
        repository.create(createProject())

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assertEquals(1, result.value.currentRow)
        assertEquals(ProjectStatus.IN_PROGRESS, result.value.status)
    }

    @Test
    fun `increment sets startedAt on first increment`() = runTest {
        repository.create(createProject())

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assertNotNull(result.value.startedAt)
    }

    @Test
    fun `increment does not overwrite existing startedAt`() = runTest {
        val startedAt = Clock.System.now()
        val project = createProject(currentRow = 5, status = ProjectStatus.IN_PROGRESS)
            .copy(startedAt = startedAt)
        repository.create(project)

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assertEquals(startedAt, result.value.startedAt)
        assertEquals(6, result.value.currentRow)
    }

    @Test
    fun `increment to totalRows marks COMPLETED`() = runTest {
        repository.create(createProject(currentRow = 99, totalRows = 100, status = ProjectStatus.IN_PROGRESS))

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assertEquals(100, result.value.currentRow)
        assertEquals(ProjectStatus.COMPLETED, result.value.status)
        assertNotNull(result.value.completedAt)
    }

    @Test
    fun `increment without totalRows never completes`() = runTest {
        repository.create(createProject(currentRow = 999, totalRows = null, status = ProjectStatus.IN_PROGRESS))

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assertEquals(1000, result.value.currentRow)
        assertEquals(ProjectStatus.IN_PROGRESS, result.value.status)
        assertNull(result.value.completedAt)
    }

    @Test
    fun `increment updates updatedAt`() = runTest {
        val project = createProject()
        repository.create(project)
        val before = Clock.System.now()

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assertTrue(result.value.updatedAt >= before)
    }

    @Test
    fun `increment returns NotFound for missing project`() = runTest {
        val result = useCase("non-existent")

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.NotFound>(result.error)
    }
}
