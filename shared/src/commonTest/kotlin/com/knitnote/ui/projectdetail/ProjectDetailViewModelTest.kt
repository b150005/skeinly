package com.knitnote.ui.projectdetail

import app.cash.turbine.test
import com.knitnote.domain.LocalUser
import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import com.knitnote.domain.usecase.AddProgressNoteUseCase
import com.knitnote.domain.usecase.CompleteProjectUseCase
import com.knitnote.domain.usecase.DecrementRowUseCase
import com.knitnote.domain.usecase.DeleteProgressNoteUseCase
import com.knitnote.domain.usecase.FakeProgressRepository
import com.knitnote.domain.usecase.FakeProjectRepository
import com.knitnote.domain.usecase.GetProgressNotesUseCase
import com.knitnote.domain.usecase.IncrementRowUseCase
import com.knitnote.domain.usecase.ReopenProjectUseCase
import com.knitnote.domain.usecase.UpdateProjectUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.time.Clock
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectDetailViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var projectRepository: FakeProjectRepository
    private lateinit var progressRepository: FakeProgressRepository

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        projectRepository = FakeProjectRepository()
        progressRepository = FakeProgressRepository()
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
        updatedAt = Clock.System.now(),
    )

    private fun createViewModel(): ProjectDetailViewModel =
        ProjectDetailViewModel(
            projectId = "test-project",
            projectRepository = projectRepository,
            incrementRow = IncrementRowUseCase(projectRepository),
            decrementRow = DecrementRowUseCase(projectRepository),
            addProgressNote = AddProgressNoteUseCase(progressRepository),
            getProgressNotes = GetProgressNotesUseCase(progressRepository),
            deleteProgressNote = DeleteProgressNoteUseCase(progressRepository),
            updateProject = UpdateProjectUseCase(projectRepository),
            completeProject = CompleteProjectUseCase(projectRepository),
            reopenProject = ReopenProjectUseCase(projectRepository),
        )

    @Test
    fun `initial state loads project`() = runTest(testDispatcher) {
        projectRepository.create(createTestProject())
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
        projectRepository.create(createTestProject())
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
        projectRepository.create(project)
        val viewModel = createViewModel()

        viewModel.state.test {
            awaitItem() // initial loaded state

            viewModel.onEvent(ProjectDetailEvent.DecrementRow)

            val updated = awaitItem()
            assertEquals(4, updated.project?.currentRow)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Progress Notes tests ---

    @Test
    fun `initial progress notes is empty`() = runTest(testDispatcher) {
        projectRepository.create(createTestProject())
        val viewModel = createViewModel()

        viewModel.progressNotes.test {
            val notes = awaitItem()
            assertTrue(notes.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `add note creates progress note for current row`() = runTest(testDispatcher) {
        val project = createTestProject().copy(
            currentRow = 5,
            status = ProjectStatus.IN_PROGRESS,
            startedAt = Clock.System.now(),
        )
        projectRepository.create(project)
        val viewModel = createViewModel()

        // Ensure state is loaded before adding note
        viewModel.state.test {
            val loaded = awaitItem()
            assertEquals(5, loaded.project?.currentRow)
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.progressNotes.test {
            awaitItem() // initial empty

            viewModel.onEvent(ProjectDetailEvent.AddNote("Decrease stitch"))

            val notes = awaitItem()
            assertEquals(1, notes.size)
            assertEquals("Decrease stitch", notes.first().note)
            assertEquals(5, notes.first().rowNumber)
            assertEquals("test-project", notes.first().projectId)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `delete note removes progress note`() = runTest(testDispatcher) {
        val project = createTestProject().copy(
            currentRow = 3,
            status = ProjectStatus.IN_PROGRESS,
            startedAt = Clock.System.now(),
        )
        projectRepository.create(project)
        val viewModel = createViewModel()

        viewModel.progressNotes.test {
            awaitItem() // initial empty

            viewModel.onEvent(ProjectDetailEvent.AddNote("Color change"))
            val notesAfterAdd = awaitItem()
            assertEquals(1, notesAfterAdd.size)
            val noteId = notesAfterAdd.first().id

            viewModel.onEvent(ProjectDetailEvent.DeleteNote(noteId))
            val notesAfterDelete = awaitItem()
            assertTrue(notesAfterDelete.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `add note uses row 0 when project not loaded`() = runTest(testDispatcher) {
        // Do NOT create project — state.value.project will be null
        val viewModel = createViewModel()

        viewModel.progressNotes.test {
            awaitItem() // initial empty

            viewModel.onEvent(ProjectDetailEvent.AddNote("Early note"))

            val notes = awaitItem()
            assertEquals(1, notes.size)
            assertEquals(0, notes.first().rowNumber)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `add multiple notes maintains all`() = runTest(testDispatcher) {
        val project = createTestProject().copy(
            currentRow = 2,
            status = ProjectStatus.IN_PROGRESS,
            startedAt = Clock.System.now(),
        )
        projectRepository.create(project)
        val viewModel = createViewModel()

        viewModel.progressNotes.test {
            awaitItem() // initial empty

            viewModel.onEvent(ProjectDetailEvent.AddNote("First note"))
            assertEquals(1, awaitItem().size)

            viewModel.onEvent(ProjectDetailEvent.AddNote("Second note"))
            val notes = awaitItem()
            assertEquals(2, notes.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Edit Project tests ---

    @Test
    fun `edit project updates title`() = runTest(testDispatcher) {
        projectRepository.create(createTestProject())
        val viewModel = createViewModel()

        viewModel.state.test {
            awaitItem() // initial loaded state

            viewModel.onEvent(ProjectDetailEvent.EditProject(title = "Updated Title", totalRows = 100))

            val updated = awaitItem()
            assertEquals("Updated Title", updated.project?.title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `edit project updates totalRows`() = runTest(testDispatcher) {
        projectRepository.create(createTestProject())
        val viewModel = createViewModel()

        viewModel.state.test {
            awaitItem() // initial loaded state

            viewModel.onEvent(ProjectDetailEvent.EditProject(title = "Test Scarf", totalRows = 200))

            val updated = awaitItem()
            assertEquals(200, updated.project?.totalRows)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- Status transition tests ---

    @Test
    fun `complete project sets COMPLETED status`() = runTest(testDispatcher) {
        val project = createTestProject().copy(
            currentRow = 5,
            status = ProjectStatus.IN_PROGRESS,
            startedAt = Clock.System.now(),
        )
        projectRepository.create(project)
        val viewModel = createViewModel()

        viewModel.state.test {
            awaitItem() // initial loaded state

            viewModel.onEvent(ProjectDetailEvent.CompleteProject)

            val updated = awaitItem()
            assertEquals(ProjectStatus.COMPLETED, updated.project?.status)
            assertNotNull(updated.project?.completedAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reopen project sets IN_PROGRESS status`() = runTest(testDispatcher) {
        val project = createTestProject().copy(
            currentRow = 50,
            status = ProjectStatus.COMPLETED,
            startedAt = Clock.System.now(),
            completedAt = Clock.System.now(),
        )
        projectRepository.create(project)
        val viewModel = createViewModel()

        viewModel.state.test {
            awaitItem() // initial loaded state

            viewModel.onEvent(ProjectDetailEvent.ReopenProject)

            val updated = awaitItem()
            assertEquals(ProjectStatus.IN_PROGRESS, updated.project?.status)
            assertEquals(null, updated.project?.completedAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `edit project with blank title sets error`() = runTest(testDispatcher) {
        projectRepository.create(createTestProject())
        val viewModel = createViewModel()

        viewModel.state.test {
            awaitItem() // initial loaded state
            cancelAndIgnoreRemainingEvents()
        }

        viewModel.onEvent(ProjectDetailEvent.EditProject(title = "", totalRows = 100))

        viewModel.error.test {
            val err = awaitItem()
            assertNotNull(err)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
