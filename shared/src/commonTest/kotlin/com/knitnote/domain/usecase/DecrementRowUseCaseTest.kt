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
import kotlin.test.assertTrue

class DecrementRowUseCaseTest {

    private lateinit var repository: FakeProjectRepository
    private lateinit var useCase: DecrementRowUseCase

    @BeforeTest
    fun setUp() {
        repository = FakeProjectRepository()
        useCase = DecrementRowUseCase(repository)
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
    fun `decrement from 0 does nothing`() = runTest {
        repository.create(createProject(currentRow = 0, status = ProjectStatus.NOT_STARTED))

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assertEquals(0, result.value.currentRow)
        assertEquals(ProjectStatus.NOT_STARTED, result.value.status)
    }

    @Test
    fun `decrement reduces current row by 1`() = runTest {
        repository.create(createProject(currentRow = 5))

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assertEquals(4, result.value.currentRow)
    }

    @Test
    fun `decrement from 1 to 0 sets NOT_STARTED and clears startedAt`() = runTest {
        repository.create(createProject(currentRow = 1))

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assertEquals(0, result.value.currentRow)
        assertEquals(ProjectStatus.NOT_STARTED, result.value.status)
        assertNull(result.value.startedAt)
    }

    @Test
    fun `decrement from completed un-completes`() = runTest {
        val project = createProject(
            currentRow = 100,
            totalRows = 100,
            status = ProjectStatus.COMPLETED,
        ).copy(completedAt = Clock.System.now())
        repository.create(project)

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assertEquals(99, result.value.currentRow)
        assertEquals(ProjectStatus.IN_PROGRESS, result.value.status)
        assertNull(result.value.completedAt)
    }

    @Test
    fun `decrement updates updatedAt`() = runTest {
        repository.create(createProject(currentRow = 5))
        val before = Clock.System.now()

        val result = assertIs<UseCaseResult.Success<Project>>(useCase("test-project"))

        assertTrue(result.value.updatedAt >= before)
    }

    @Test
    fun `decrement returns NotFound for missing project`() = runTest {
        val result = useCase("non-existent")

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.NotFound>(result.error)
    }
}
