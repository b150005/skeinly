package com.knitnote.domain.usecase

import com.knitnote.domain.LocalUser
import com.knitnote.domain.model.ProjectStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreateProjectUseCaseTest {

    private lateinit var repository: FakeProjectRepository
    private lateinit var useCase: CreateProjectUseCase

    @BeforeTest
    fun setUp() {
        repository = FakeProjectRepository()
        useCase = CreateProjectUseCase(repository)
    }

    @Test
    fun `creates project with correct defaults`() = runTest {
        val project = useCase(title = "My Scarf", totalRows = 100)

        assertTrue(project.id.isNotBlank())
        assertEquals("My Scarf", project.title)
        assertEquals(100, project.totalRows)
        assertEquals(0, project.currentRow)
        assertEquals(ProjectStatus.NOT_STARTED, project.status)
        assertEquals(LocalUser.ID, project.ownerId)
        assertEquals(LocalUser.DEFAULT_PATTERN_ID, project.patternId)
        assertNull(project.startedAt)
        assertNull(project.completedAt)
        assertNotNull(project.createdAt)
    }

    @Test
    fun `creates project without total rows`() = runTest {
        val project = useCase(title = "Free-form Project", totalRows = null)

        assertNull(project.totalRows)
        assertEquals("Free-form Project", project.title)
    }

    @Test
    fun `project is persisted in repository`() = runTest {
        val project = useCase(title = "Test", totalRows = 50)

        val retrieved = repository.getById(project.id)
        assertNotNull(retrieved)
        assertEquals(project.id, retrieved.id)
    }
}
