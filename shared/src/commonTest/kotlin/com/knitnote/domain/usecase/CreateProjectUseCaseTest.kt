package com.knitnote.domain.usecase

import com.knitnote.domain.LocalUser
import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreateProjectUseCaseTest {

    private lateinit var repository: FakeProjectRepository
    private lateinit var authRepository: FakeAuthRepository
    private lateinit var useCase: CreateProjectUseCase

    @BeforeTest
    fun setUp() {
        repository = FakeProjectRepository()
        authRepository = FakeAuthRepository()
        useCase = CreateProjectUseCase(repository, authRepository)
    }

    @Test
    fun `creates project with correct defaults`() = runTest {
        val result = assertIs<UseCaseResult.Success<Project>>(useCase(title = "My Scarf", totalRows = 100))
        val project = result.value

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
        val result = assertIs<UseCaseResult.Success<Project>>(useCase(title = "Free-form Project", totalRows = null))

        assertNull(result.value.totalRows)
        assertEquals("Free-form Project", result.value.title)
    }

    @Test
    fun `project is persisted in repository`() = runTest {
        val result = assertIs<UseCaseResult.Success<Project>>(useCase(title = "Test", totalRows = 50))

        val retrieved = repository.getById(result.value.id)
        assertNotNull(retrieved)
        assertEquals(result.value.id, retrieved.id)
    }

    @Test
    fun `creates project with updatedAt equal to createdAt`() = runTest {
        val result = assertIs<UseCaseResult.Success<Project>>(useCase(title = "Test", totalRows = 50))

        assertEquals(result.value.createdAt, result.value.updatedAt)
    }

    @Test
    fun `blank title returns Validation error`() = runTest {
        val result = useCase(title = "  ", totalRows = 50)

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
    }

    @Test
    fun `empty title returns Validation error`() = runTest {
        val result = useCase(title = "", totalRows = 50)

        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
    }
}
