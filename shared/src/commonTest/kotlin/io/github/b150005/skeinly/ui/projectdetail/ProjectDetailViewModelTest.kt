package io.github.b150005.skeinly.ui.projectdetail

import app.cash.turbine.test
import io.github.b150005.skeinly.data.analytics.AnalyticsEvent
import io.github.b150005.skeinly.data.analytics.AnalyticsTracker
import io.github.b150005.skeinly.data.analytics.RecordingAnalyticsTracker
import io.github.b150005.skeinly.data.remote.FakeRemoteStorageDataSource
import io.github.b150005.skeinly.domain.LocalUser
import io.github.b150005.skeinly.domain.model.AuthState
import io.github.b150005.skeinly.domain.model.ChartCell
import io.github.b150005.skeinly.domain.model.ChartExtents
import io.github.b150005.skeinly.domain.model.ChartLayer
import io.github.b150005.skeinly.domain.model.CoordinateSystem
import io.github.b150005.skeinly.domain.model.Pattern
import io.github.b150005.skeinly.domain.model.Project
import io.github.b150005.skeinly.domain.model.ProjectSegment
import io.github.b150005.skeinly.domain.model.ProjectStatus
import io.github.b150005.skeinly.domain.model.SegmentState
import io.github.b150005.skeinly.domain.model.SharePermission
import io.github.b150005.skeinly.domain.model.StorageVariant
import io.github.b150005.skeinly.domain.model.StructuredChart
import io.github.b150005.skeinly.domain.model.User
import io.github.b150005.skeinly.domain.model.Visibility
import io.github.b150005.skeinly.domain.usecase.AddProgressNoteUseCase
import io.github.b150005.skeinly.domain.usecase.CompleteProjectUseCase
import io.github.b150005.skeinly.domain.usecase.DecrementRowUseCase
import io.github.b150005.skeinly.domain.usecase.DeleteChartImageUseCase
import io.github.b150005.skeinly.domain.usecase.DeleteProgressNoteUseCase
import io.github.b150005.skeinly.domain.usecase.DeleteProgressPhotoUseCase
import io.github.b150005.skeinly.domain.usecase.FakeAuthRepository
import io.github.b150005.skeinly.domain.usecase.FakePatternRepository
import io.github.b150005.skeinly.domain.usecase.FakeProgressRepository
import io.github.b150005.skeinly.domain.usecase.FakeProjectRepository
import io.github.b150005.skeinly.domain.usecase.FakeProjectSegmentRepository
import io.github.b150005.skeinly.domain.usecase.FakeShareRepository
import io.github.b150005.skeinly.domain.usecase.FakeStructuredChartRepository
import io.github.b150005.skeinly.domain.usecase.FakeUserRepository
import io.github.b150005.skeinly.domain.usecase.GetProgressNotesUseCase
import io.github.b150005.skeinly.domain.usecase.IncrementRowUseCase
import io.github.b150005.skeinly.domain.usecase.ObserveProjectSegmentsUseCase
import io.github.b150005.skeinly.domain.usecase.ObserveStructuredChartUseCase
import io.github.b150005.skeinly.domain.usecase.ReopenProjectUseCase
import io.github.b150005.skeinly.domain.usecase.ResetProjectProgressUseCase
import io.github.b150005.skeinly.domain.usecase.ShareProjectUseCase
import io.github.b150005.skeinly.domain.usecase.UpdateProjectUseCase
import io.github.b150005.skeinly.domain.usecase.UploadChartImageUseCase
import io.github.b150005.skeinly.domain.usecase.UploadProgressPhotoUseCase
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
    private val userRepository = FakeUserRepository()

    private fun createViewModel(analyticsTracker: AnalyticsTracker? = null): ProjectDetailViewModel =
        ProjectDetailViewModel(
            projectId = "test-project",
            projectRepository = projectRepository,
            patternRepository = patternRepository,
            userRepository = userRepository,
            incrementRow = IncrementRowUseCase(projectRepository),
            decrementRow = DecrementRowUseCase(projectRepository),
            addProgressNote = AddProgressNoteUseCase(progressRepository, authRepository),
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
            uploadProgressPhoto = UploadProgressPhotoUseCase(null, authRepository),
            deleteProgressPhoto = DeleteProgressPhotoUseCase(null, authRepository),
            progressPhotoStorage = null,
            observeStructuredChart = ObserveStructuredChartUseCase(FakeStructuredChartRepository()),
            observeProjectSegments = ObserveProjectSegmentsUseCase(FakeProjectSegmentRepository()),
            resetProjectProgress = ResetProjectProgressUseCase(FakeProjectSegmentRepository()),
            analyticsTracker = analyticsTracker,
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
                var withLink = awaitItem()
                // Skip intermediate emissions from pattern flow
                while (withLink.shareLink == null) {
                    withLink = awaitItem()
                }
                assertNotNull(withLink.shareLink)

                viewModel.onEvent(ProjectDetailEvent.DismissShareDialog)
                var dismissed = awaitItem()
                while (dismissed.shareLink != null) {
                    dismissed = awaitItem()
                }
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
            userRepository = userRepository,
            incrementRow = IncrementRowUseCase(projectRepository),
            decrementRow = DecrementRowUseCase(projectRepository),
            addProgressNote = AddProgressNoteUseCase(progressRepository, authRepository),
            getProgressNotes = GetProgressNotesUseCase(progressRepository),
            deleteProgressNote = DeleteProgressNoteUseCase(progressRepository),
            updateProject = UpdateProjectUseCase(projectRepository),
            completeProject = CompleteProjectUseCase(projectRepository),
            reopenProject = ReopenProjectUseCase(projectRepository),
            shareProject =
                ShareProjectUseCase(
                    projectRepository = projectRepository,
                    patternRepository = patternRepository,
                    shareRepository = shareRepo,
                    authRepository = authRepository,
                ),
            uploadChartImage = UploadChartImageUseCase(patternRepository, null, authRepository),
            deleteChartImage = DeleteChartImageUseCase(patternRepository, null),
            remoteStorage = null,
            uploadProgressPhoto = UploadProgressPhotoUseCase(null, authRepository),
            deleteProgressPhoto = DeleteProgressPhotoUseCase(null, authRepository),
            progressPhotoStorage = null,
            observeStructuredChart = ObserveStructuredChartUseCase(FakeStructuredChartRepository()),
            observeProjectSegments = ObserveProjectSegmentsUseCase(FakeProjectSegmentRepository()),
            resetProjectProgress = ResetProjectProgressUseCase(FakeProjectSegmentRepository()),
        )

    private fun createViewModelWithStorage(storage: FakeRemoteStorageDataSource = FakeRemoteStorageDataSource()): ProjectDetailViewModel =
        ProjectDetailViewModel(
            projectId = "test-project",
            projectRepository = projectRepository,
            patternRepository = patternRepository,
            userRepository = userRepository,
            incrementRow = IncrementRowUseCase(projectRepository),
            decrementRow = DecrementRowUseCase(projectRepository),
            addProgressNote = AddProgressNoteUseCase(progressRepository, authRepository),
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
            uploadChartImage = UploadChartImageUseCase(patternRepository, storage, authRepository),
            deleteChartImage = DeleteChartImageUseCase(patternRepository, storage),
            remoteStorage = storage,
            uploadProgressPhoto = UploadProgressPhotoUseCase(storage, authRepository),
            deleteProgressPhoto = DeleteProgressPhotoUseCase(storage, authRepository),
            progressPhotoStorage = storage,
            observeStructuredChart = ObserveStructuredChartUseCase(FakeStructuredChartRepository()),
            observeProjectSegments = ObserveProjectSegmentsUseCase(FakeProjectSegmentRepository()),
            resetProjectProgress = ResetProjectProgressUseCase(FakeProjectSegmentRepository()),
        )

    /** Minimal valid JPEG bytes with SOI and EOI markers for test data. */
    private fun jpegHeader(): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0xFF.toByte(), 0xD9.toByte())

    // --- Phase 34 US-7: segment progress summary ---

    private fun createViewModelWithChartAndSegments(
        chartRepo: FakeStructuredChartRepository,
        segmentRepo: FakeProjectSegmentRepository,
    ): ProjectDetailViewModel =
        ProjectDetailViewModel(
            projectId = "test-project",
            projectRepository = projectRepository,
            patternRepository = patternRepository,
            userRepository = userRepository,
            incrementRow = IncrementRowUseCase(projectRepository),
            decrementRow = DecrementRowUseCase(projectRepository),
            addProgressNote = AddProgressNoteUseCase(progressRepository, authRepository),
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
            uploadProgressPhoto = UploadProgressPhotoUseCase(null, authRepository),
            deleteProgressPhoto = DeleteProgressPhotoUseCase(null, authRepository),
            progressPhotoStorage = null,
            observeStructuredChart = ObserveStructuredChartUseCase(chartRepo),
            observeProjectSegments = ObserveProjectSegmentsUseCase(segmentRepo),
            resetProjectProgress = ResetProjectProgressUseCase(segmentRepo),
        )

    private fun buildChart(
        patternId: String,
        layers: List<ChartLayer>,
    ): StructuredChart =
        StructuredChart(
            id = "chart-$patternId",
            patternId = patternId,
            ownerId = LocalUser.ID,
            schemaVersion = StructuredChart.CURRENT_SCHEMA_VERSION,
            storageVariant = StorageVariant.INLINE,
            coordinateSystem = CoordinateSystem.RECT_GRID,
            extents = ChartExtents.Rect(minX = 0, maxX = 2, minY = 0, maxY = 1),
            layers = layers,
            revisionId = "rev-1",
            parentRevisionId = null,
            contentHash = "h1-00000000",
            createdAt = Clock.System.now(),
            updatedAt = Clock.System.now(),
        )

    private fun segment(
        projectId: String,
        layerId: String,
        x: Int,
        y: Int,
        state: SegmentState,
    ): ProjectSegment =
        ProjectSegment(
            id = ProjectSegment.buildId(projectId, layerId, x, y),
            projectId = projectId,
            layerId = layerId,
            cellX = x,
            cellY = y,
            state = state,
            updatedAt = Clock.System.now(),
        )

    private fun createProjectWithPattern(patternId: String): Project = createTestProject().copy(patternId = patternId)

    @Test
    fun `segment summary is null when no structured chart`() =
        runTest(testDispatcher) {
            projectRepository.create(createProjectWithPattern("pat-1"))
            patternRepository.create(createTestPattern().copy(id = "pat-1"))
            // Chart repo empty on purpose.
            val viewModel =
                createViewModelWithChartAndSegments(
                    chartRepo = FakeStructuredChartRepository(),
                    segmentRepo = FakeProjectSegmentRepository(),
                )

            viewModel.state.test {
                val state = awaitItem()
                assertFalse(state.hasStructuredChart)
                assertNull(state.segmentsDone)
                assertNull(state.segmentsTotal)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `segment summary reports zero done when chart has cells but no segments`() =
        runTest(testDispatcher) {
            projectRepository.create(createProjectWithPattern("pat-1"))
            patternRepository.create(createTestPattern().copy(id = "pat-1"))
            val chartRepo = FakeStructuredChartRepository()
            chartRepo.seed(
                buildChart(
                    patternId = "pat-1",
                    layers =
                        listOf(
                            ChartLayer(
                                id = "L1",
                                name = "Main",
                                visible = true,
                                cells =
                                    listOf(
                                        ChartCell(symbolId = "jis.knit.k", x = 0, y = 0),
                                        ChartCell(symbolId = "jis.knit.k", x = 1, y = 0),
                                        ChartCell(symbolId = "jis.knit.k", x = 2, y = 0),
                                    ),
                            ),
                        ),
                ),
            )
            val viewModel = createViewModelWithChartAndSegments(chartRepo, FakeProjectSegmentRepository())

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.hasStructuredChart)
                assertEquals(0, state.segmentsDone)
                assertEquals(3, state.segmentsTotal)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `segment summary counts only DONE segments on visible layers`() =
        runTest(testDispatcher) {
            projectRepository.create(createProjectWithPattern("pat-1"))
            patternRepository.create(createTestPattern().copy(id = "pat-1"))
            val chartRepo = FakeStructuredChartRepository()
            chartRepo.seed(
                buildChart(
                    patternId = "pat-1",
                    layers =
                        listOf(
                            ChartLayer(
                                id = "L1",
                                name = "Main",
                                visible = true,
                                cells =
                                    listOf(
                                        ChartCell(symbolId = "jis.knit.k", x = 0, y = 0),
                                        ChartCell(symbolId = "jis.knit.k", x = 1, y = 0),
                                        ChartCell(symbolId = "jis.knit.k", x = 2, y = 0),
                                    ),
                            ),
                        ),
                ),
            )
            val segmentRepo = FakeProjectSegmentRepository()
            segmentRepo.seed(segment("test-project", "L1", 0, 0, SegmentState.DONE))
            segmentRepo.seed(segment("test-project", "L1", 1, 0, SegmentState.WIP))
            val viewModel = createViewModelWithChartAndSegments(chartRepo, segmentRepo)

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(1, state.segmentsDone) // WIP not counted
                assertEquals(3, state.segmentsTotal)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `segment summary excludes hidden-layer cells and segments`() =
        runTest(testDispatcher) {
            projectRepository.create(createProjectWithPattern("pat-1"))
            patternRepository.create(createTestPattern().copy(id = "pat-1"))
            val chartRepo = FakeStructuredChartRepository()
            chartRepo.seed(
                buildChart(
                    patternId = "pat-1",
                    layers =
                        listOf(
                            ChartLayer(
                                id = "L1",
                                name = "Main",
                                visible = true,
                                cells =
                                    listOf(
                                        ChartCell(symbolId = "jis.knit.k", x = 0, y = 0),
                                        ChartCell(symbolId = "jis.knit.k", x = 1, y = 0),
                                    ),
                            ),
                            ChartLayer(
                                id = "L2",
                                name = "Hidden",
                                visible = false,
                                cells =
                                    listOf(
                                        ChartCell(symbolId = "jis.knit.k", x = 0, y = 1),
                                        ChartCell(symbolId = "jis.knit.k", x = 1, y = 1),
                                    ),
                            ),
                        ),
                ),
            )
            val segmentRepo = FakeProjectSegmentRepository()
            segmentRepo.seed(segment("test-project", "L1", 0, 0, SegmentState.DONE))
            // Hidden-layer DONE segment must NOT count toward `done` (matches
            // overlay semantics — AC-1.3 — hiding a layer hides its progress).
            segmentRepo.seed(segment("test-project", "L2", 0, 1, SegmentState.DONE))
            val viewModel = createViewModelWithChartAndSegments(chartRepo, segmentRepo)

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(1, state.segmentsDone)
                assertEquals(2, state.segmentsTotal) // only visible layer cells
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `segment summary excludes orphan DONE segments when cell was removed from chart`() =
        runTest(testDispatcher) {
            projectRepository.create(createProjectWithPattern("pat-1"))
            patternRepository.create(createTestPattern().copy(id = "pat-1"))
            val chartRepo = FakeStructuredChartRepository()
            // Chart has 2 placed cells at (0,0) and (1,0).
            chartRepo.seed(
                buildChart(
                    patternId = "pat-1",
                    layers =
                        listOf(
                            ChartLayer(
                                id = "L1",
                                name = "Main",
                                visible = true,
                                cells =
                                    listOf(
                                        ChartCell(symbolId = "jis.knit.k", x = 0, y = 0),
                                        ChartCell(symbolId = "jis.knit.k", x = 1, y = 0),
                                    ),
                            ),
                        ),
                ),
            )
            val segmentRepo = FakeProjectSegmentRepository()
            segmentRepo.seed(segment("test-project", "L1", 0, 0, SegmentState.DONE))
            // Orphan: this segment references a cell the chart no longer has
            // (e.g., user deleted (5, 5) after marking it done).
            segmentRepo.seed(segment("test-project", "L1", 5, 5, SegmentState.DONE))
            val viewModel = createViewModelWithChartAndSegments(chartRepo, segmentRepo)

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(1, state.segmentsDone) // Orphan NOT counted.
                assertEquals(2, state.segmentsTotal)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `segment summary works for polar charts`() =
        runTest(testDispatcher) {
            projectRepository.create(createProjectWithPattern("pat-1"))
            patternRepository.create(createTestPattern().copy(id = "pat-1"))
            val chartRepo = FakeStructuredChartRepository()
            val polarChart =
                StructuredChart(
                    id = "chart-polar",
                    patternId = "pat-1",
                    ownerId = LocalUser.ID,
                    schemaVersion = StructuredChart.CURRENT_SCHEMA_VERSION,
                    storageVariant = StorageVariant.INLINE,
                    coordinateSystem = CoordinateSystem.POLAR_ROUND,
                    extents = ChartExtents.Polar(rings = 2, stitchesPerRing = listOf(6, 12)),
                    layers =
                        listOf(
                            ChartLayer(
                                id = "L1",
                                name = "Ring 0",
                                visible = true,
                                cells =
                                    listOf(
                                        ChartCell(symbolId = "jis.crochet.sc", x = 0, y = 0),
                                        ChartCell(symbolId = "jis.crochet.sc", x = 1, y = 0),
                                    ),
                            ),
                        ),
                    revisionId = "rev-1",
                    parentRevisionId = null,
                    contentHash = "h1-00000000",
                    createdAt = Clock.System.now(),
                    updatedAt = Clock.System.now(),
                )
            chartRepo.seed(polarChart)
            val segmentRepo = FakeProjectSegmentRepository()
            segmentRepo.seed(segment("test-project", "L1", 0, 0, SegmentState.DONE))
            val viewModel = createViewModelWithChartAndSegments(chartRepo, segmentRepo)

            viewModel.state.test {
                val state = awaitItem()
                // Polar charts count cells the same way — coordinate system
                // doesn't affect the summary. (Phase 34 defers polar tap-to-
                // progress UI, but existing segments still summarize correctly.)
                assertEquals(1, state.segmentsDone)
                assertEquals(2, state.segmentsTotal)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `segment summary is null when chart has zero visible placed cells`() =
        runTest(testDispatcher) {
            projectRepository.create(createProjectWithPattern("pat-1"))
            patternRepository.create(createTestPattern().copy(id = "pat-1"))
            val chartRepo = FakeStructuredChartRepository()
            // Chart exists but has only a hidden layer (no visible cells to summarize).
            chartRepo.seed(
                buildChart(
                    patternId = "pat-1",
                    layers =
                        listOf(
                            ChartLayer(
                                id = "L1",
                                name = "Hidden",
                                visible = false,
                                cells = listOf(ChartCell(symbolId = "jis.knit.k", x = 0, y = 0)),
                            ),
                        ),
                ),
            )
            val viewModel = createViewModelWithChartAndSegments(chartRepo, FakeProjectSegmentRepository())

            viewModel.state.test {
                val state = awaitItem()
                assertTrue(state.hasStructuredChart)
                assertNull(state.segmentsDone)
                assertNull(state.segmentsTotal)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // --- Phase 36.5: parent-pattern attribution ---

    private fun createTestUser(
        id: String,
        displayName: String,
    ): User =
        User(
            id = id,
            displayName = displayName,
            avatarUrl = null,
            bio = null,
            createdAt = Clock.System.now(),
        )

    // The ViewModel resolves the source pattern + author regardless of the
    // source's `visibility` value (PUBLIC, SHARED, or PRIVATE) — the
    // tappability gate `visibility == PUBLIC` lives at the Compose / SwiftUI
    // layer, NOT in the ViewModel. This test exercises the PUBLIC branch
    // because it is the most common case; PRIVATE/SHARED resolution behavior
    // is structurally identical.
    @Test
    fun `parent attribution resolves source pattern and author when forked`() =
        runTest(testDispatcher) {
            val sourcePattern =
                createTestPattern().copy(
                    id = "source-pat",
                    ownerId = "source-user",
                    title = "Cable Beanie",
                    visibility = Visibility.PUBLIC,
                )
            val forkedPattern =
                createTestPattern().copy(
                    id = "forked-pat",
                    ownerId = LocalUser.ID,
                    title = "Cable Beanie",
                    parentPatternId = "source-pat",
                )
            patternRepository.create(sourcePattern)
            patternRepository.create(forkedPattern)
            userRepository.addUser(createTestUser("source-user", "Alice"))
            projectRepository.create(createProjectWithPattern("forked-pat"))

            val viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                while (state.parentPattern == null) {
                    state = awaitItem()
                }
                assertEquals("source-pat", state.parentPattern?.id)
                assertEquals("Cable Beanie", state.parentPattern?.title)
                assertEquals(Visibility.PUBLIC, state.parentPattern?.visibility)
                assertEquals("Alice", state.parentPatternAuthor?.displayName)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `parent attribution leaves parentPattern null when source was deleted`() =
        runTest(testDispatcher) {
            // Forked pattern carries `parentPatternId = "deleted-pat"` but the
            // source row is absent — UI renders `state_forked_from_deleted`.
            val forkedPattern =
                createTestPattern().copy(
                    id = "forked-pat",
                    ownerId = LocalUser.ID,
                    parentPatternId = "deleted-pat",
                )
            patternRepository.create(forkedPattern)
            projectRepository.create(createProjectWithPattern("forked-pat"))

            val viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                // Wait for the pattern flow to surface the forked pattern, then
                // confirm parentPattern stays null (source row absent from repo).
                while (state.pattern?.parentPatternId != "deleted-pat") {
                    state = awaitItem()
                }
                assertEquals("deleted-pat", state.pattern?.parentPatternId)
                assertNull(state.parentPattern)
                assertNull(state.parentPatternAuthor)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `parent attribution falls back gracefully when author lookup returns null`() =
        runTest(testDispatcher) {
            // Source pattern resolves but author was deleted from users table —
            // UI renders the parametric template with `label_someone` fallback
            // per Phase 33.1.7 ActivityFeed precedent.
            val sourcePattern =
                createTestPattern().copy(
                    id = "source-pat",
                    ownerId = "ghost-user",
                    visibility = Visibility.PUBLIC,
                )
            val forkedPattern =
                createTestPattern().copy(
                    id = "forked-pat",
                    ownerId = LocalUser.ID,
                    parentPatternId = "source-pat",
                )
            patternRepository.create(sourcePattern)
            patternRepository.create(forkedPattern)
            // Deliberately NOT adding "ghost-user" to userRepository.
            projectRepository.create(createProjectWithPattern("forked-pat"))

            val viewModel = createViewModel()

            viewModel.state.test {
                var state = awaitItem()
                while (state.parentPattern == null) {
                    state = awaitItem()
                }
                assertEquals("source-pat", state.parentPattern?.id)
                assertNull(state.parentPatternAuthor)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `IncrementRow captures row_incremented analytics event on success`() =
        runTest(testDispatcher) {
            projectRepository.create(createTestProject())
            val tracker = RecordingAnalyticsTracker()
            val viewModel = createViewModel(tracker)
            viewModel.state.test {
                awaitItem() // initial loaded state
                viewModel.onEvent(ProjectDetailEvent.IncrementRow)
                awaitItem() // row incremented
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals(listOf<AnalyticsEvent>(AnalyticsEvent.RowIncremented), tracker.captured)
        }

    @Test
    fun `DecrementRow does not capture analytics event in Phase F-3`() =
        runTest(testDispatcher) {
            // Phase F.3 ships row_incremented only; decrement is Phase F.4+ if at all.
            projectRepository.create(createTestProject().copy(currentRow = 5))
            val tracker = RecordingAnalyticsTracker()
            val viewModel = createViewModel(tracker)
            viewModel.state.test {
                awaitItem() // initial loaded state
                viewModel.onEvent(ProjectDetailEvent.DecrementRow)
                awaitItem() // row decremented
                cancelAndIgnoreRemainingEvents()
            }
            assertTrue(tracker.captured.isEmpty(), "decrement should not capture in Phase F.3")
        }
}
