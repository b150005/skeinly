package com.knitnote.ui.projectdetail

import app.cash.turbine.test
import com.knitnote.domain.LocalUser
import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import com.knitnote.domain.usecase.DecrementRowUseCase
import com.knitnote.domain.usecase.FakeProjectRepository
import com.knitnote.domain.usecase.IncrementRowUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectDetailViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: FakeProjectRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeProjectRepository()
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createTestProject(): Project = Project(
        id = "test-project",
        ownerId = LocalUser.ID,
        patternId = LocalUser.DEFAULT_PATTERN_ID,
        title = "Test Scarf",
        status = ProjectStatus.NOT_STARTED,
        currentRow = 0,
        totalRows = 100,
        startedAt = null,
        completedAt = null,
        createdAt = Clock.System.now(),
    )

    private fun createViewModel(): ProjectDetailViewModel =
        ProjectDetailViewModel(
            projectId = "test-project",
            projectRepository = repository,
            incrementRow = IncrementRowUseCase(repository),
            decrementRow = DecrementRowUseCase(repository),
        )

    @Test
    fun `initial state loads project`() = runTest(testDispatcher) {
        repository.create(createTestProject())
        val viewModel = createViewModel()

        viewModel.state.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertNotNull(state.project)
            assertEquals("Test Scarf", state.project?.title)
            assertEquals(0, state.project?.currentRow)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `increment row updates state`() = runTest(testDispatcher) {
        repository.create(createTestProject())
        val viewModel = createViewModel()

        viewModel.state.test {
            awaitItem() // initial loaded state

            viewModel.onEvent(ProjectDetailEvent.IncrementRow)

            val updated = awaitItem()
            assertEquals(1, updated.project?.currentRow)
            assertEquals(ProjectStatus.IN_PROGRESS, updated.project?.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `decrement row updates state`() = runTest(testDispatcher) {
        val project = createTestProject().copy(
            currentRow = 5,
            status = ProjectStatus.IN_PROGRESS,
            startedAt = Clock.System.now(),
        )
        repository.create(project)
        val viewModel = createViewModel()

        viewModel.state.test {
            awaitItem() // initial loaded state

            viewModel.onEvent(ProjectDetailEvent.DecrementRow)

            val updated = awaitItem()
            assertEquals(4, updated.project?.currentRow)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
