package com.knitnote.domain.usecase

import com.knitnote.domain.LocalUser
import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    )

    @Test
    fun `decrement from 0 does nothing`() = runTest {
        repository.create(createProject(currentRow = 0, status = ProjectStatus.NOT_STARTED))

        val result = useCase("test-project")

        assertEquals(0, result.currentRow)
        assertEquals(ProjectStatus.NOT_STARTED, result.status)
    }

    @Test
    fun `decrement reduces current row by 1`() = runTest {
        repository.create(createProject(currentRow = 5))

        val result = useCase("test-project")

        assertEquals(4, result.currentRow)
    }

    @Test
    fun `decrement from 1 to 0 sets NOT_STARTED and clears startedAt`() = runTest {
        repository.create(createProject(currentRow = 1))

        val result = useCase("test-project")

        assertEquals(0, result.currentRow)
        assertEquals(ProjectStatus.NOT_STARTED, result.status)
        assertNull(result.startedAt)
    }

    @Test
    fun `decrement from completed un-completes`() = runTest {
        val project = createProject(
            currentRow = 100,
            totalRows = 100,
            status = ProjectStatus.COMPLETED,
        ).copy(completedAt = Clock.System.now())
        repository.create(project)

        val result = useCase("test-project")

        assertEquals(99, result.currentRow)
        assertEquals(ProjectStatus.IN_PROGRESS, result.status)
        assertNull(result.completedAt)
    }
}
