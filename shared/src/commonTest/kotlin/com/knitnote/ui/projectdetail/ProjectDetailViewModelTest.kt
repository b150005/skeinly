package com.knitnote.ui.projectdetail

import app.cash.turbine.test
import com.knitnote.data.remote.FakeRemoteStorageDataSource
import com.knitnote.domain.LocalUser
import com.knitnote.domain.model.AuthState
import com.knitnote.domain.model.Pattern
import com.knitnote.domain.model.Project
import com.knitnote.domain.model.ProjectStatus
import com.knitnote.domain.model.SharePermission
import com.knitnote.domain.model.Visibility
import com.knitnote.domain.usecase.AddProgressNoteUseCase
import com.knitnote.domain.usecase.CompleteProjectUseCase
import com.knitnote.domain.usecase.DecrementRowUseCase
import com.knitnote.domain.usecase.DeleteChartImageUseCase
import com.knitnote.domain.usecase.DeleteProgressNoteUseCase
import com.knitnote.domain.usecase.FakeAuthRepository
import com.knitnote.domain.usecase.FakePatternRepository
import com.knitnote.domain.usecase.FakeProgressRepository
import com.knitnote.domain.usecase.FakeProjectRepository
import com.knitnote.domain.usecase.FakeShareRepository
import com.knitnote.domain.usecase.GetProgressNotesUseCase
import com.knitnote.domain.usecase.IncrementRowUseCase
import com.knitnote.domain.usecase.ReopenProjectUseCase
import com.knitnote.domain.usecase.ShareProjectUseCase
import com.knitnote.domain.usecase.UpdateProjectUseCase
import com.knitnote.domain.usecase.UploadChartImageUseCase
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
import kotlin.time.Clock

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

    private fun createTestProject(): Project =
        Project(
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

    private val patternRepository = FakePatternRepository()
    private val authRepository = FakeAuthRepository()

    private fun createViewModel(): ProjectDetailViewModel =
        ProjectDetailViewModel(
            projectId = "test-project",
            projectRepository = projectRepository,
            patternRepository = patternRepository,
            incrementRow = IncrementRowUseCase(projectRepository),
            decrementRow = DecrementRowUseCase(projectRepository),
            addProgressNote = AddProgressNoteUseCase(progressRepository),
            getProgressNotes = GetProgressNotesUseCase(progressRepository),
            deleteProgressNote = DeleteProgressNoteUseCase(progressRepository),
            updateProject = UpdateProjectUseCase(projectRepository),
            completeProject = CompleteProjectUseCase(projectRepository),
            reopenProject = ReopenProjectUseCase(projectRepository),
            shareProject =
                ShareProjectUseCase(
                    projectRepository = projectRepository,
                    patternRepository = patternRepository,
                    shareRepository = null,
                    authRepository = authRepository,
                ),
            uploadChartImage = UploadChartImageUseCase(patternRepository, null, authRepository),
            deleteChartImage = DeleteChartImageUseCase(patternRepository, null),
            remoteStorage = null,
        )

    @Test
    fun `initial state loads project`() =
        runTest(testDispatcher) {
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
    fun `increment row updates state`() =
        runTest(testDispatcher) {
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
    fun `decrement row updates state`() =
        runTest(testDispatcher) {
            val project =
                createTestProject().copy(
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
    fun `initial progress notes is empty`() =
        runTest(testDispatcher) {
            projectRepository.create(createTestProject())
            val viewModel = createViewModel()

            viewModel.progressNotes.test {
                val notes = awaitItem()
                assertTrue(notes.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `add note creates progress note for current row`() =
        runTest(testDispatcher) {
            val project =
                createTestProject().copy(
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
    fun `delete note removes progress note`() =
        runTest(testDispatcher) {
            val project =
                createTestProject().copy(
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
    fun `add note uses row 0 when project not loaded`() =
        runTest(testDispatcher) {
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
    fun `add multiple notes maintains all`() =
        runTest(testDispatcher) {
            val project =
                createTestProject().copy(
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
    fun `edit project updates title`() =
        runTest(testDispatcher) {
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
    fun `edit project updates totalRows`() =
        runTest(testDispatcher) {
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
    fun `complete project sets COMPLETED status`() =
        runTest(testDispatcher) {
            val project =
                createTestProject().copy(
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
    fun `reopen project sets IN_PROGRESS status`() =
        runTest(testDispatcher) {
            val project =
                createTestProject().copy(
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
    fun `edit project with blank title sets error`() =
        runTest(testDispatcher) {
            projectRepository.create(createTestProject())
            val viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // initial loaded state
                cancelAndIgnoreRemainingEvents()
            }

            viewModel.onEvent(ProjectDetailEvent.EditProject(title = "", totalRows = 100))

            viewModel.state.test {
                val s = awaitItem()
                assertNotNull(s.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- Share tests ---

    @Test
    fun `ShareProject sets error when share repo is null`() =
        runTest(testDispatcher) {
            projectRepository.create(createTestProject())
            val viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // initial loaded
                viewModel.onEvent(ProjectDetailEvent.ShareProject)
                val updated = awaitItem()
                assertNotNull(updated.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `ShareProject success sets shareLink`() =
        runTest(testDispatcher) {
            authRepository.setAuthState(AuthState.Authenticated(userId = LocalUser.ID, email = "test@test.com"))
            projectRepository.create(createTestProject())
            val shareRepo = FakeShareRepository()
            val viewModel = createViewModelWithShare(shareRepo)

            viewModel.state.test {
                awaitItem() // initial loaded
                viewModel.onEvent(ProjectDetailEvent.ShareProject)
                val updated = awaitItem()
                assertNotNull(updated.shareLink)
                assertNotNull(updated.shareLink?.patternId)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `DismissShareDialog clears shareLink`() =
        runTest(testDispatcher) {
            authRepository.setAuthState(AuthState.Authenticated(userId = LocalUser.ID, email = "test@test.com"))
            projectRepository.create(createTestProject())
            val shareRepo = FakeShareRepository()
            val viewModel = createViewModelWithShare(shareRepo)

            viewModel.state.test {
                awaitItem()
                viewModel.onEvent(ProjectDetailEvent.ShareProject)
                val withLink = awaitItem()
                assertNotNull(withLink.shareLink)

                viewModel.onEvent(ProjectDetailEvent.DismissShareDialog)
                val dismissed = awaitItem()
                assertNull(dismissed.shareLink)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `ShareWithUser success sends channel event`() =
        runTest(testDispatcher) {
            authRepository.setAuthState(AuthState.Authenticated(userId = LocalUser.ID, email = "test@test.com"))
            projectRepository.create(createTestProject())
            val shareRepo = FakeShareRepository()
            val viewModel = createViewModelWithShare(shareRepo)

            viewModel.state.test {
                awaitItem() // loaded
                cancelAndIgnoreRemainingEvents()
            }

            viewModel.directShareSuccess.test {
                viewModel.onEvent(ProjectDetailEvent.ShareWithUser("other-user", SharePermission.VIEW))
                awaitItem() // Unit event received
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- Chart Image tests ---

    @Test
    fun `SelectChartImage updates selected index`() =
        runTest(testDispatcher) {
            projectRepository.create(createTestProject())
            val viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // loaded
                viewModel.onEvent(ProjectDetailEvent.SelectChartImage(2))
                val updated = awaitItem()
                assertEquals(2, updated.selectedChartImageIndex)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `CloseChartViewer clears selected index`() =
        runTest(testDispatcher) {
            projectRepository.create(createTestProject())
            val viewModel = createViewModel()

            viewModel.state.test {
                awaitItem()
                viewModel.onEvent(ProjectDetailEvent.SelectChartImage(1))
                awaitItem()
                viewModel.onEvent(ProjectDetailEvent.CloseChartViewer)
                val updated = awaitItem()
                assertNull(updated.selectedChartImageIndex)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `UploadChartImage sets error when storage is null`() =
        runTest(testDispatcher) {
            projectRepository.create(createTestProject())
            val viewModel = createViewModel()

            viewModel.state.test {
                awaitItem() // loaded
                viewModel.onEvent(ProjectDetailEvent.UploadChartImage(jpegHeader(), "test.jpg"))
                val updated = awaitItem()
                assertFalse(updated.isUploadingImage) // upload finished
                assertNotNull(updated.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `UploadChartImage sets error when project not loaded`() =
        runTest(testDispatcher) {
            // Don't create project — state.project will be null
            val viewModel = createViewModelWithStorage()

            viewModel.state.test {
                awaitItem() // initial (no project)
                viewModel.onEvent(ProjectDetailEvent.UploadChartImage(jpegHeader(), "test.jpg"))
                val updated = awaitItem()
                assertNotNull(updated.error)
                assertFalse(updated.isUploadingImage)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `UploadChartImage success updates chart paths`() =
        runTest(testDispatcher) {
            authRepository.setAuthState(AuthState.Authenticated(userId = LocalUser.ID, email = "test@test.com"))
            val pattern = createTestPattern()
            patternRepository.create(pattern)
            projectRepository.create(createTestProject())
            val storage = FakeRemoteStorageDataSource()
            val viewModel = createViewModelWithStorage(storage)

            viewModel.state.test {
                awaitItem() // loaded
                viewModel.onEvent(ProjectDetailEvent.UploadChartImage(jpegHeader(), "chart.jpg"))
                // UnconfinedTestDispatcher collapses intermediate states — collect final state
                val final = awaitItem()
                assertFalse(final.isUploadingImage)
                assertTrue(final.chartImagePaths.isNotEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `DeleteChartImage sets error when project not loaded`() =
        runTest(testDispatcher) {
            // Don't create project
            val viewModel = createViewModel()

            viewModel.state.test {
                val initial = awaitItem()
                viewModel.onEvent(ProjectDetailEvent.DeleteChartImage("some/path.jpg"))
                // Silent no-op: early return with null project, no error set
                assertNull(initial.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `DeleteChartImage sets error when storage is null`() =
        runTest(testDispatcher) {
            val pattern = createTestPattern()
            patternRepository.create(pattern)
            projectRepository.create(createTestProject())
            val viewModel = createViewModel() // remoteStorage = null

            viewModel.state.test {
                awaitItem() // loaded
                viewModel.onEvent(ProjectDetailEvent.DeleteChartImage("some/path.jpg"))
                val updated = awaitItem()
                assertNotNull(updated.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `ClearError clears error state`() =
        runTest(testDispatcher) {
            projectRepository.create(createTestProject())
            val viewModel = createViewModel()

            viewModel.state.test {
                awaitItem()
                // Trigger an error first
                viewModel.onEvent(ProjectDetailEvent.ShareProject)
                val withError = awaitItem()
                assertNotNull(withError.error)

                viewModel.onEvent(ProjectDetailEvent.ClearError)
                val cleared = awaitItem()
                assertNull(cleared.error)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `loadChartImages populates paths when pattern has chart urls`() =
        runTest(testDispatcher) {
            val pattern = createTestPattern().copy(chartImageUrls = listOf("user/pat/chart.jpg"))
            patternRepository.create(pattern)
            val storage = FakeRemoteStorageDataSource()
            projectRepository.create(createTestProject())
            val viewModel = createViewModelWithStorage(storage)

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.chartImagePaths.isNotEmpty())
                assertEquals("user/pat/chart.jpg", state.chartImagePaths.first())
                assertTrue(state.chartImageSignedUrls.isNotEmpty())
                assertTrue(state.chartImageSignedUrls.first().contains("signed"))
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- Helpers ---

    private fun createTestPattern(): Pattern =
        Pattern(
            id = LocalUser.DEFAULT_PATTERN_ID,
            ownerId = LocalUser.ID,
            title = "Test Pattern",
            description = null,
            difficulty = null,
            gauge = null,
            yarnInfo = null,
            needleSize = null,
            chartImageUrls = emptyList(),
            visibility = Visibility.PRIVATE,
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )

    private fun createViewModelWithShare(shareRepo: FakeShareRepository): ProjectDetailViewModel =
        ProjectDetailViewModel(
            projectId = "test-project",
            projectRepository = projectRepository,
            patternRepository = patternRepository,
            incrementRow = IncrementRowUseCase(projectRepository),
            decrementRow = DecrementRowUseCase(projectRepository),
            addProgressNote = AddProgressNoteUseCase(progressRepository),
            getProgressNotes = GetProgressNotesUseCase(progressRepository),
            deleteProgressNote = DeleteProgressNoteUseCase(progressRepository),
            updateProject = UpdateProjectUseCase(projectRepository),
            completeProject = CompleteProjectUseCase(projectRepository),
            reopenProject = ReopenProjectUseCase(projectRepository),
            shareProject = ShareProjectUseCase(
                projectRepository = projectRepository,
                patternRepository = patternRepository,
                shareRepository = shareRepo,
                authRepository = authRepository,
            ),
            uploadChartImage = UploadChartImageUseCase(patternRepository, null, authRepository),
            deleteChartImage = DeleteChartImageUseCase(patternRepository, null),
            remoteStorage = null,
        )

    private fun createViewModelWithStorage(
        storage: FakeRemoteStorageDataSource = FakeRemoteStorageDataSource(),
    ): ProjectDetailViewModel =
        ProjectDetailViewModel(
            projectId = "test-project",
            projectRepository = projectRepository,
            patternRepository = patternRepository,
            incrementRow = IncrementRowUseCase(projectRepository),
            decrementRow = DecrementRowUseCase(projectRepository),
            addProgressNote = AddProgressNoteUseCase(progressRepository),
            getProgressNotes = GetProgressNotesUseCase(progressRepository),
            deleteProgressNote = DeleteProgressNoteUseCase(progressRepository),
            updateProject = UpdateProjectUseCase(projectRepository),
            completeProject = CompleteProjectUseCase(projectRepository),
            reopenProject = ReopenProjectUseCase(projectRepository),
            shareProject = ShareProjectUseCase(
                projectRepository = projectRepository,
                patternRepository = patternRepository,
                shareRepository = null,
                authRepository = authRepository,
            ),
            uploadChartImage = UploadChartImageUseCase(patternRepository, storage, authRepository),
            deleteChartImage = DeleteChartImageUseCase(patternRepository, storage),
            remoteStorage = storage,
        )

    /** Minimal valid JPEG header bytes for test data. */
    private fun jpegHeader(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
}
