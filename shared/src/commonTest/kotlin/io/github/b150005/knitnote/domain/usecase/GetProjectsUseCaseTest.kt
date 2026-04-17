package io.github.b150005.knitnote.domain.usecase

import app.cash.turbine.test
import io.github.b150005.knitnote.domain.LocalUser
import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class GetProjectsUseCaseTest {
    private lateinit var projectRepo: FakeProjectRepository
    private lateinit var authRepo: FakeAuthRepository
    private lateinit var useCase: GetProjectsUseCase

    @BeforeTest
    fun setUp() {
        projectRepo = FakeProjectRepository()
        authRepo = FakeAuthRepository()
        useCase = GetProjectsUseCase(projectRepo, authRepo)
    }

    private fun testProject(
        id: String,
        ownerId: String = LocalUser.ID,
    ) = Project(
        id = id,
        ownerId = ownerId,
        patternId = LocalUser.DEFAULT_PATTERN_ID,
        title = "Project $id",
        status = ProjectStatus.NOT_STARTED,
        currentRow = 0,
        totalRows = 50,
        startedAt = null,
        completedAt = null,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    @Test
    fun `returns projects for local user when unauthenticated`() =
        runTest {
            projectRepo.create(testProject("p-1"))
            projectRepo.create(testProject("p-2"))

            useCase().test {
                val projects = awaitItem()
                assertEquals(2, projects.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `returns projects for authenticated user`() =
        runTest {
            authRepo.setAuthState(AuthState.Authenticated("user-a", "a@test.com"))
            projectRepo.create(testProject("p-1", ownerId = "user-a"))
            projectRepo.create(testProject("p-2", ownerId = "other-user"))

            useCase().test {
                val projects = awaitItem()
                assertEquals(1, projects.size)
                assertEquals("p-1", projects.first().id)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `returns empty list when no projects`() =
        runTest {
            useCase().test {
                val projects = awaitItem()
                assertTrue(projects.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }
}
