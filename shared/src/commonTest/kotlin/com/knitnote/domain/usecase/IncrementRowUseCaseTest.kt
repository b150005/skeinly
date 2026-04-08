package com.knitnote.domain.usecase

import com.knitnote.domain.LocalUser
import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    )

    @Test
    fun `increment from 0 changes status to IN_PROGRESS`() = runTest {
        repository.create(createProject())

        val result = useCase("test-project")

        assertEquals(1, result.currentRow)
        assertEquals(ProjectStatus.IN_PROGRESS, result.status)
    }

    @Test
    fun `increment sets startedAt on first increment`() = runTest {
        repository.create(createProject())

        val result = useCase("test-project")

        assertNotNull(result.startedAt)
    }

    @Test
    fun `increment does not overwrite existing startedAt`() = runTest {
        val startedAt = Clock.System.now()
        val project = createProject(currentRow = 5, status = ProjectStatus.IN_PROGRESS)
            .copy(startedAt = startedAt)
        repository.create(project)

        val result = useCase("test-project")

        assertEquals(startedAt, result.startedAt)
        assertEquals(6, result.currentRow)
    }

    @Test
    fun `increment to totalRows marks COMPLETED`() = runTest {
        repository.create(createProject(currentRow = 99, totalRows = 100, status = ProjectStatus.IN_PROGRESS))

        val result = useCase("test-project")

        assertEquals(100, result.currentRow)
        assertEquals(ProjectStatus.COMPLETED, result.status)
        assertNotNull(result.completedAt)
    }

    @Test
    fun `increment without totalRows never completes`() = runTest {
        repository.create(createProject(currentRow = 999, totalRows = null, status = ProjectStatus.IN_PROGRESS))

        val result = useCase("test-project")

        assertEquals(1000, result.currentRow)
        assertEquals(ProjectStatus.IN_PROGRESS, result.status)
        assertNull(result.completedAt)
    }
}
