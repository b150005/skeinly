package io.github.b150005.knitnote.ui.projectlist

import app.cash.turbine.test
import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.model.Project
import io.github.b150005.knitnote.domain.model.ProjectStatus
import io.github.b150005.knitnote.domain.model.SortOrder
import io.github.b150005.knitnote.domain.usecase.CloseRealtimeChannelsUseCase
import io.github.b150005.knitnote.domain.usecase.CreateProjectUseCase
import io.github.b150005.knitnote.domain.usecase.DeleteProjectUseCase
import io.github.b150005.knitnote.domain.usecase.ErrorMessage
import io.github.b150005.knitnote.domain.usecase.FakeAuthRepository
import io.github.b150005.knitnote.domain.usecase.FakePatternRepository
import io.github.b150005.knitnote.domain.usecase.FakeProjectRepository
import io.github.b150005.knitnote.domain.usecase.GetPatternsUseCase
import io.github.b150005.knitnote.domain.usecase.GetProjectsUseCase
import io.github.b150005.knitnote.domain.usecase.SignOutUseCase
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
import kotlin.time.Duration.Companion.hours

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectListViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: FakeProjectRepository
    private lateinit var viewModel: ProjectListViewModel
    private val fakeAuth = FakeAuthRepository()
    private val ownerId = "user-1"

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = FakeProjectRepository()
        fakeAuth.setAuthState(AuthState.Authenticated(ownerId, "test@example.com"))
        viewModel =
            ProjectListViewModel(
                getProjects = GetProjectsUseCase(repository, fakeAuth),
                getPatterns = GetPatternsUseCase(FakePatternRepository(), fakeAuth),
                createProject = CreateProjectUseCase(repository, fakeAuth),
                deleteProject = DeleteProjectUseCase(repository),
                signOut = SignOutUseCase(fakeAuth, CloseRealtimeChannelsUseCase(null, null, null)),
            )
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun testProject(
        id: String = "p-${Clock.System.now().toEpochMilliseconds()}",
        title: String = "Test Project",
        status: ProjectStatus = ProjectStatus.NOT_STARTED,
        currentRow: Int = 0,
        totalRows: Int? = null,
        createdAt: kotlin.time.Instant = Clock.System.now(),
    ): Project =
        Project(
            id = id,
            ownerId = ownerId,
            patternId = "pattern-1",
            title = title,
            status = status,
            currentRow = currentRow,
            totalRows = totalRows,
            startedAt = null,
            completedAt = null,
            createdAt = createdAt,
            updatedAt = createdAt,
        )

    // region Existing tests

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
                assertEquals(ErrorMessage.Generic, state.error)
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

    // endregion

    // region Initial state defaults

    @Test
    fun `initial state has empty search query`() =
        runTest(testDispatcher) {
            viewModel.state.test {
                val state = awaitItem()
                assertEquals("", state.searchQuery)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `initial state has null status filter`() =
        runTest(testDispatcher) {
            viewModel.state.test {
                val state = awaitItem()
                assertNull(state.statusFilter)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `initial state has RECENT sort order`() =
        runTest(testDispatcher) {
            viewModel.state.test {
                val state = awaitItem()
                assertEquals(SortOrder.RECENT, state.sortOrder)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // endregion

    // region Search tests

    @Test
    fun `search with empty query returns all projects`() =
        runTest(testDispatcher) {
            val now = Clock.System.now()
            repository.create(testProject(id = "1", title = "Wool Scarf", createdAt = now))
            repository.create(testProject(id = "2", title = "Cotton Hat", createdAt = now - 1.hours))

            viewModel.state.test {
                val initial = awaitItem()
                assertEquals(2, initial.projects.size)
                assertEquals("", initial.searchQuery)
                // Empty query by default returns all projects
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `search by title substring filters projects`() =
        runTest(testDispatcher) {
            val now = Clock.System.now()
            repository.create(testProject(id = "1", title = "Wool Scarf", createdAt = now))
            repository.create(testProject(id = "2", title = "Cotton Hat", createdAt = now - 1.hours))
            repository.create(testProject(id = "3", title = "Wool Blanket", createdAt = now - 2.hours))

            viewModel.state.test {
                awaitItem() // initial with all 3

                viewModel.onEvent(ProjectListEvent.UpdateSearchQuery("wool"))

                val state = awaitItem()
                assertEquals(2, state.projects.size)
                assertTrue(state.projects.all { it.title.contains("Wool", ignoreCase = true) })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `search is case-insensitive`() =
        runTest(testDispatcher) {
            repository.create(testProject(id = "1", title = "WOOL SCARF"))

            viewModel.state.test {
                awaitItem() // initial

                viewModel.onEvent(ProjectListEvent.UpdateSearchQuery("wool"))

                val state = awaitItem()
                assertEquals(1, state.projects.size)
                assertEquals("WOOL SCARF", state.projects[0].title)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `search with no matches returns empty list`() =
        runTest(testDispatcher) {
            repository.create(testProject(id = "1", title = "Wool Scarf"))

            viewModel.state.test {
                awaitItem() // initial with 1

                viewModel.onEvent(ProjectListEvent.UpdateSearchQuery("xyz"))

                val state = awaitItem()
                assertTrue(state.projects.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `search preserves status filter`() =
        runTest(testDispatcher) {
            val now = Clock.System.now()
            repository.create(
                testProject(id = "1", title = "Wool Scarf", status = ProjectStatus.IN_PROGRESS, createdAt = now),
            )
            repository.create(
                testProject(id = "2", title = "Wool Hat", status = ProjectStatus.COMPLETED, createdAt = now - 1.hours),
            )
            repository.create(
                testProject(id = "3", title = "Cotton Gloves", status = ProjectStatus.IN_PROGRESS, createdAt = now - 2.hours),
            )

            viewModel.state.test {
                awaitItem() // initial with all 3

                viewModel.onEvent(ProjectListEvent.UpdateStatusFilter(ProjectStatus.IN_PROGRESS))
                awaitItem() // 2 in-progress

                viewModel.onEvent(ProjectListEvent.UpdateSearchQuery("wool"))

                val state = awaitItem()
                assertEquals(1, state.projects.size)
                assertEquals("Wool Scarf", state.projects[0].title)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `clearing search query restores all projects`() =
        runTest(testDispatcher) {
            repository.create(testProject(id = "1", title = "Wool Scarf"))
            repository.create(testProject(id = "2", title = "Cotton Hat"))

            viewModel.state.test {
                awaitItem() // initial with 2

                viewModel.onEvent(ProjectListEvent.UpdateSearchQuery("wool"))
                assertEquals(1, awaitItem().projects.size)

                viewModel.onEvent(ProjectListEvent.UpdateSearchQuery(""))

                val state = awaitItem()
                assertEquals(2, state.projects.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `search query is trimmed`() =
        runTest(testDispatcher) {
            repository.create(testProject(id = "1", title = "Wool Scarf"))

            viewModel.state.test {
                awaitItem() // initial

                viewModel.onEvent(ProjectListEvent.UpdateSearchQuery("  wool  "))

                val state = awaitItem()
                assertEquals(1, state.projects.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // endregion

    // region Status filter tests

    @Test
    fun `filter by NOT_STARTED shows only not started projects`() =
        runTest(testDispatcher) {
            val now = Clock.System.now()
            repository.create(testProject(id = "1", status = ProjectStatus.NOT_STARTED, createdAt = now))
            repository.create(testProject(id = "2", status = ProjectStatus.IN_PROGRESS, createdAt = now - 1.hours))
            repository.create(testProject(id = "3", status = ProjectStatus.COMPLETED, createdAt = now - 2.hours))

            viewModel.state.test {
                awaitItem() // initial with all 3

                viewModel.onEvent(ProjectListEvent.UpdateStatusFilter(ProjectStatus.NOT_STARTED))

                val state = awaitItem()
                assertEquals(1, state.projects.size)
                assertEquals(ProjectStatus.NOT_STARTED, state.projects[0].status)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `filter by IN_PROGRESS shows only in progress projects`() =
        runTest(testDispatcher) {
            val now = Clock.System.now()
            repository.create(testProject(id = "1", status = ProjectStatus.NOT_STARTED, createdAt = now))
            repository.create(testProject(id = "2", status = ProjectStatus.IN_PROGRESS, createdAt = now - 1.hours))

            viewModel.state.test {
                awaitItem() // initial

                viewModel.onEvent(ProjectListEvent.UpdateStatusFilter(ProjectStatus.IN_PROGRESS))

                val state = awaitItem()
                assertEquals(1, state.projects.size)
                assertEquals(ProjectStatus.IN_PROGRESS, state.projects[0].status)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `filter by COMPLETED shows only completed projects`() =
        runTest(testDispatcher) {
            val now = Clock.System.now()
            repository.create(testProject(id = "1", status = ProjectStatus.COMPLETED, createdAt = now))
            repository.create(testProject(id = "2", status = ProjectStatus.IN_PROGRESS, createdAt = now - 1.hours))

            viewModel.state.test {
                awaitItem() // initial

                viewModel.onEvent(ProjectListEvent.UpdateStatusFilter(ProjectStatus.COMPLETED))

                val state = awaitItem()
                assertEquals(1, state.projects.size)
                assertEquals(ProjectStatus.COMPLETED, state.projects[0].status)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `null status filter shows all projects`() =
        runTest(testDispatcher) {
            val now = Clock.System.now()
            repository.create(testProject(id = "1", status = ProjectStatus.NOT_STARTED, createdAt = now))
            repository.create(testProject(id = "2", status = ProjectStatus.IN_PROGRESS, createdAt = now - 1.hours))
            repository.create(testProject(id = "3", status = ProjectStatus.COMPLETED, createdAt = now - 2.hours))

            viewModel.state.test {
                awaitItem() // initial with all 3

                viewModel.onEvent(ProjectListEvent.UpdateStatusFilter(ProjectStatus.COMPLETED))
                assertEquals(1, awaitItem().projects.size)

                viewModel.onEvent(ProjectListEvent.UpdateStatusFilter(null))

                val state = awaitItem()
                assertEquals(3, state.projects.size)
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `changing status filter updates filtered list`() =
        runTest(testDispatcher) {
            val now = Clock.System.now()
            repository.create(testProject(id = "1", status = ProjectStatus.NOT_STARTED, createdAt = now))
            repository.create(testProject(id = "2", status = ProjectStatus.IN_PROGRESS, createdAt = now - 1.hours))

            viewModel.state.test {
                awaitItem() // initial

                viewModel.onEvent(ProjectListEvent.UpdateStatusFilter(ProjectStatus.NOT_STARTED))
                assertEquals(1, awaitItem().projects.size)

                viewModel.onEvent(ProjectListEvent.UpdateStatusFilter(ProjectStatus.IN_PROGRESS))

                val state = awaitItem()
                assertEquals(1, state.projects.size)
                assertEquals(ProjectStatus.IN_PROGRESS, state.projects[0].status)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // endregion

    // region Sort tests

    @Test
    fun `sort by RECENT orders by createdAt descending`() =
        runTest(testDispatcher) {
            val now = Clock.System.now()
            repository.create(testProject(id = "1", title = "Old", createdAt = now - 2.hours))
            repository.create(testProject(id = "2", title = "New", createdAt = now))
            repository.create(testProject(id = "3", title = "Mid", createdAt = now - 1.hours))

            viewModel.state.test {
                val state = awaitItem()
                assertEquals(listOf("New", "Mid", "Old"), state.projects.map { it.title })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `sort by ALPHABETICAL orders by title ascending case-insensitive`() =
        runTest(testDispatcher) {
            val now = Clock.System.now()
            repository.create(testProject(id = "1", title = "cotton Hat", createdAt = now))
            repository.create(testProject(id = "2", title = "Alpaca Scarf", createdAt = now - 1.hours))
            repository.create(testProject(id = "3", title = "Wool Blanket", createdAt = now - 2.hours))

            viewModel.state.test {
                awaitItem() // initial (RECENT order)

                viewModel.onEvent(ProjectListEvent.UpdateSortOrder(SortOrder.ALPHABETICAL))

                val state = awaitItem()
                assertEquals(listOf("Alpaca Scarf", "cotton Hat", "Wool Blanket"), state.projects.map { it.title })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `sort by PROGRESS orders by progress percentage descending`() =
        runTest(testDispatcher) {
            val now = Clock.System.now()
            repository.create(testProject(id = "1", title = "25%", currentRow = 25, totalRows = 100, createdAt = now))
            repository.create(testProject(id = "2", title = "75%", currentRow = 75, totalRows = 100, createdAt = now - 1.hours))
            repository.create(testProject(id = "3", title = "50%", currentRow = 50, totalRows = 100, createdAt = now - 2.hours))

            viewModel.state.test {
                awaitItem() // initial (RECENT order)

                viewModel.onEvent(ProjectListEvent.UpdateSortOrder(SortOrder.PROGRESS))

                val state = awaitItem()
                assertEquals(listOf("75%", "50%", "25%"), state.projects.map { it.title })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `sort by PROGRESS places projects without totalRows at end`() =
        runTest(testDispatcher) {
            val now = Clock.System.now()
            repository.create(testProject(id = "1", title = "No Total", currentRow = 10, totalRows = null, createdAt = now))
            repository.create(testProject(id = "2", title = "50%", currentRow = 50, totalRows = 100, createdAt = now - 1.hours))

            viewModel.state.test {
                awaitItem() // initial

                viewModel.onEvent(ProjectListEvent.UpdateSortOrder(SortOrder.PROGRESS))

                val state = awaitItem()
                assertEquals(listOf("50%", "No Total"), state.projects.map { it.title })
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `sort works together with search and status filter`() =
        runTest(testDispatcher) {
            val now = Clock.System.now()
            repository.create(
                testProject(
                    id = "1",
                    title = "Wool Scarf",
                    status = ProjectStatus.IN_PROGRESS,
                    currentRow = 75,
                    totalRows = 100,
                    createdAt = now,
                ),
            )
            repository.create(
                testProject(
                    id = "2",
                    title = "Wool Blanket",
                    status = ProjectStatus.IN_PROGRESS,
                    currentRow = 25,
                    totalRows = 100,
                    createdAt = now - 1.hours,
                ),
            )
            repository.create(
                testProject(
                    id = "3",
                    title = "Cotton Hat",
                    status = ProjectStatus.IN_PROGRESS,
                    currentRow = 50,
                    totalRows = 100,
                    createdAt = now - 2.hours,
                ),
            )
            repository.create(
                testProject(
                    id = "4",
                    title = "Wool Hat",
                    status = ProjectStatus.COMPLETED,
                    currentRow = 100,
                    totalRows = 100,
                    createdAt = now - 3.hours,
                ),
            )

            viewModel.state.test {
                awaitItem() // initial with all 4

                // Filter: IN_PROGRESS only
                viewModel.onEvent(ProjectListEvent.UpdateStatusFilter(ProjectStatus.IN_PROGRESS))
                awaitItem() // 3 in-progress

                // Search: "wool" only
                viewModel.onEvent(ProjectListEvent.UpdateSearchQuery("wool"))
                awaitItem() // 2 wool + in-progress

                // Sort: by progress descending
                viewModel.onEvent(ProjectListEvent.UpdateSortOrder(SortOrder.PROGRESS))

                val state = awaitItem()
                assertEquals(2, state.projects.size)
                assertEquals(listOf("Wool Scarf", "Wool Blanket"), state.projects.map { it.title })
                cancelAndIgnoreRemainingEvents()
            }
        }

    // endregion
}
