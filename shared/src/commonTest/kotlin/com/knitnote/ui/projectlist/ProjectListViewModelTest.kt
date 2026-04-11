package com.knitnote.ui.projectlist

import app.cash.turbine.test
import com.knitnote.domain.model.AuthState
import com.knitnote.domain.usecase.CreateProjectUseCase
import com.knitnote.domain.usecase.DeleteProjectUseCase
import com.knitnote.domain.usecase.FakeAuthRepository
import com.knitnote.domain.usecase.FakeProjectRepository
import com.knitnote.domain.usecase.GetProjectsUseCase
import com.knitnote.domain.usecase.SignOutUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectListViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: FakeProjectRepository
    private lateinit var viewModel: ProjectListViewModel
    private val fakeAuth = FakeAuthRepository()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeProjectRepository()
        fakeAuth.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))
        viewModel =
            ProjectListViewModel(
                getProjects = GetProjectsUseCase(repository, fakeAuth),
                createProject = CreateProjectUseCase(repository, fakeAuth),
                deleteProject = DeleteProjectUseCase(repository),
                signOut = SignOutUseCase(fakeAuth, null, null, null),
            )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state loads empty project list`() =
        runTest(testDispatcher) {
            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.isLoading)
                assertTrue(state.projects.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `create project adds to list`() =
        runTest(testDispatcher) {
            viewModel.state.test {
                awaitItem() // initial empty

                viewModel.onEvent(ProjectListEvent.CreateProject("Test Scarf", 100))

                val state = awaitItem()
                assertEquals(1, state.projects.size)
                assertEquals("Test Scarf", state.projects[0].title)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `delete project removes from list`() =
        runTest(testDispatcher) {
            viewModel.state.test {
                awaitItem() // initial empty

                viewModel.onEvent(ProjectListEvent.CreateProject("To Delete", null))
                val stateAfterCreate = awaitItem()
                val projectId = stateAfterCreate.projects[0].id

                viewModel.onEvent(ProjectListEvent.DeleteProject(projectId))

                val state = awaitItem()
                assertTrue(state.projects.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `sign out clears auth state`() =
        runTest(testDispatcher) {
            viewModel.state.test {
                awaitItem() // initial

                viewModel.onEvent(ProjectListEvent.SignOut)

                // Auth state should be cleared (NavGraph handles navigation)
                assertNull(fakeAuth.getCurrentUserId())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `sign out failure shows error`() =
        runTest(testDispatcher) {
            fakeAuth.signOutError = RuntimeException("Network error")

            viewModel.state.test {
                awaitItem() // initial

                viewModel.onEvent(ProjectListEvent.SignOut)

                val state = awaitItem()
                assertEquals("Network error", state.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `show and dismiss create dialog`() =
        runTest(testDispatcher) {
            viewModel.state.test {
                awaitItem() // initial empty

                viewModel.onEvent(ProjectListEvent.ShowCreateDialog)
                assertTrue(awaitItem().showCreateDialog)

                viewModel.onEvent(ProjectListEvent.DismissCreateDialog)
                assertFalse(awaitItem().showCreateDialog)

                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `create project with blank title shows error`() =
        runTest(testDispatcher) {
            viewModel.state.test {
                awaitItem() // initial

                viewModel.onEvent(ProjectListEvent.CreateProject("", null))

                val state = awaitItem()
                assertNotNull(state.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `ClearError clears error`() =
        runTest(testDispatcher) {
            viewModel.state.test {
                awaitItem()
                viewModel.onEvent(ProjectListEvent.CreateProject("", null))
                val withError = awaitItem()
                assertNotNull(withError.error)

                viewModel.onEvent(ProjectListEvent.ClearError)
                val cleared = awaitItem()
                assertNull(cleared.error)
                cancelAndIgnoreRemainingEvents()
            }
        }
}
