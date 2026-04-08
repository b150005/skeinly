package com.knitnote.ui.projectlist

import app.cash.turbine.test
import com.knitnote.domain.usecase.CreateProjectUseCase
import com.knitnote.domain.usecase.DeleteProjectUseCase
import com.knitnote.domain.usecase.FakeProjectRepository
import com.knitnote.domain.usecase.GetProjectsUseCase
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
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectListViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: FakeProjectRepository
    private lateinit var viewModel: ProjectListViewModel

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeProjectRepository()
        viewModel = ProjectListViewModel(
            getProjects = GetProjectsUseCase(repository),
            createProject = CreateProjectUseCase(repository),
            deleteProject = DeleteProjectUseCase(repository),
        )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state loads empty project list`() = runTest(testDispatcher) {
        val state = viewModel.state.value
        assertFalse(state.isLoading)
        assertTrue(state.projects.isEmpty())
    }

    @Test
    fun `create project adds to list`() = runTest(testDispatcher) {
        viewModel.onEvent(ProjectListEvent.CreateProject("Test Scarf", 100))

        val state = viewModel.state.value
        assertEquals(1, state.projects.size)
        assertEquals("Test Scarf", state.projects[0].title)
    }

    @Test
    fun `delete project removes from list`() = runTest(testDispatcher) {
        viewModel.onEvent(ProjectListEvent.CreateProject("To Delete", null))
        val projectId = viewModel.state.value.projects[0].id

        viewModel.onEvent(ProjectListEvent.DeleteProject(projectId))

        val state = viewModel.state.value
        assertTrue(state.projects.isEmpty())
    }

    @Test
    fun `show and dismiss create dialog`() = runTest(testDispatcher) {
        viewModel.onEvent(ProjectListEvent.ShowCreateDialog)
        assertTrue(viewModel.state.value.showCreateDialog)

        viewModel.onEvent(ProjectListEvent.DismissCreateDialog)
        assertFalse(viewModel.state.value.showCreateDialog)
    }
}
