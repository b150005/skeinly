package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.LocalUser
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Clock

class GetProjectByIdUseCaseTest {
    private lateinit var repository: FakeProjectRepository
    private lateinit var useCase: GetProjectByIdUseCase

    @BeforeTest
    fun setUp() {
        repository = FakeProjectRepository()
        useCase = GetProjectByIdUseCase(repository)
    }

    private fun testProject(id: String = "p-1") =
        Project(
            id = id,
            ownerId = LocalUser.ID,
            patternId = LocalUser.DEFAULT_PATTERN_ID,
            title = "Test Scarf",
            status = ProjectStatus.NOT_STARTED,
            currentRow = 0,
            totalRows = 100,
            startedAt = null,
            completedAt = null,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )

    @Test
    fun `returns project when found`() =
        runTest {
            val project = testProject()
            repository.create(project)

            val result = assertIs<UseCaseResult.Success<Project>>(useCase("p-1"))
            assertEquals("Test Scarf", result.value.title)
        }

    @Test
    fun `returns NotFound when project does not exist`() =
        runTest {
            val result = assertIs<UseCaseResult.Failure>(useCase("nonexistent"))
            assertIs<UseCaseError.NotFound>(result.error)
        }
}
