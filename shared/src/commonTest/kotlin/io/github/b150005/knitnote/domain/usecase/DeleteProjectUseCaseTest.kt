package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.LocalUser
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Clock

class DeleteProjectUseCaseTest {
    private lateinit var repository: FakeProjectRepository
    private lateinit var useCase: DeleteProjectUseCase

    @BeforeTest
    fun setUp() {
        repository = FakeProjectRepository()
        useCase = DeleteProjectUseCase(repository)
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
    fun `deletes existing project successfully`() =
        runTest {
            repository.create(testProject())

            val result = assertIs<UseCaseResult.Success<Unit>>(useCase("p-1"))
            assertNull(repository.getById("p-1"))
        }

    @Test
    fun `returns failure when repository throws`() =
        runTest {
            repository.shouldThrowOnDelete = true
            repository.create(testProject())

            val result = assertIs<UseCaseResult.Failure>(useCase("p-1"))
            assertIs<UseCaseError.Unknown>(result.error)
        }
}
