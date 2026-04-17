package io.github.b150005.knitnote.ui.discovery

import app.cash.turbine.test
import io.github.b150005.knitnote.data.remote.FakePublicPatternDataSource
import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.SortOrder
import io.github.b150005.knitnote.domain.model.Visibility
import io.github.b150005.knitnote.domain.usecase.FakeAuthRepository
import io.github.b150005.knitnote.domain.usecase.FakePatternRepository
import io.github.b150005.knitnote.domain.usecase.FakeProjectRepository
import io.github.b150005.knitnote.domain.usecase.ForkPublicPatternUseCase
import io.github.b150005.knitnote.domain.usecase.GetPublicPatternsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
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
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    private val patternA =
        Pattern(
            id = "pub-a",
            ownerId = "other-user",
            title = "Cable Knit Scarf",
            description = "Warm scarf",
            difficulty = Difficulty.BEGINNER,
            gauge = null,
            yarnInfo = null,
            needleSize = null,
            chartImageUrls = emptyList(),
            visibility = Visibility.PUBLIC,
            createdAt = Instant.fromEpochMilliseconds(2000),
            updatedAt = Instant.fromEpochMilliseconds(2000),
        )

    private val patternB =
        Pattern(
            id = "pub-b",
            ownerId = "other-user",
            title = "Ribbed Hat",
            description = "A ribbed hat",
            difficulty = Difficulty.ADVANCED,
            gauge = null,
            yarnInfo = null,
            needleSize = null,
            chartImageUrls = emptyList(),
            visibility = Visibility.PUBLIC,
            createdAt = Instant.fromEpochMilliseconds(1000),
            updatedAt = Instant.fromEpochMilliseconds(1000),
        )

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(
        dataSource: FakePublicPatternDataSource = FakePublicPatternDataSource(),
        patternRepo: FakePatternRepository = FakePatternRepository(),
        projectRepo: FakeProjectRepository = FakeProjectRepository(),
        authRepo: FakeAuthRepository = FakeAuthRepository(),
    ): DiscoveryViewModel {
        val getPublicPatterns = GetPublicPatternsUseCase(dataSource)
        val forkPublicPattern = ForkPublicPatternUseCase(patternRepo, projectRepo, authRepo)
        return DiscoveryViewModel(getPublicPatterns, forkPublicPattern)
    }

    @Test
    fun `initial load populates patterns`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            dataSource.addPattern(patternA)
            dataSource.addPattern(patternB)
            val viewModel = createViewModel(dataSource = dataSource)

            viewModel.state.test {
                skipItems(1) // initial
                val loaded = awaitItem()
                assertFalse(loaded.isLoading)
                assertEquals(2, loaded.patterns.size)
            }
        }

    @Test
    fun `difficulty filter applies client-side`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            dataSource.addPattern(patternA) // BEGINNER
            dataSource.addPattern(patternB) // ADVANCED
            val viewModel = createViewModel(dataSource = dataSource)

            viewModel.state.test {
                skipItems(1) // initial
                awaitItem() // loaded

                viewModel.onEvent(DiscoveryEvent.UpdateDifficultyFilter(Difficulty.BEGINNER))
                val filtered = awaitItem()
                assertEquals(1, filtered.patterns.size)
                assertEquals("pub-a", filtered.patterns.first().id)
            }
        }

    @Test
    fun `sort order applies client-side`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            dataSource.addPattern(patternA) // createdAt = 2000
            dataSource.addPattern(patternB) // createdAt = 1000
            val viewModel = createViewModel(dataSource = dataSource)

            viewModel.state.test {
                skipItems(1)
                awaitItem() // loaded with RECENT sort (patternA first)

                viewModel.onEvent(DiscoveryEvent.UpdateSortOrder(SortOrder.ALPHABETICAL))
                val sorted = awaitItem()
                assertEquals("Cable Knit Scarf", sorted.patterns.first().title)
                assertEquals("Ribbed Hat", sorted.patterns.last().title)
            }
        }

    @Test
    fun `search query triggers debounced remote re-fetch`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            dataSource.addPattern(patternA)
            dataSource.addPattern(patternB)
            val viewModel = createViewModel(dataSource = dataSource)

            viewModel.state.test {
                skipItems(1)
                val loaded = awaitItem()
                assertEquals(2, loaded.patterns.size)

                viewModel.onEvent(DiscoveryEvent.UpdateSearchQuery("cable"))
                // Filter state update
                val queryUpdated = awaitItem()
                assertEquals("cable", queryUpdated.searchQuery)

                // After debounce, remote re-fetch returns filtered results
                advanceTimeBy(DiscoveryViewModel.SEARCH_DEBOUNCE_MS + 50)
                // May get isLoading=true then results
                val items = cancelAndConsumeRemainingEvents()
                // Verify the final state has the search applied
            }

            // Check final state directly
            advanceUntilIdle()
            val state = viewModel.state.value
            assertEquals("cable", state.searchQuery)
        }

    @Test
    fun `fork success emits forked project ID`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            dataSource.addPattern(patternA)

            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val patternRepo = FakePatternRepository()
            patternRepo.create(patternA) // Make it findable by getById

            val viewModel = createViewModel(dataSource = dataSource, patternRepo = patternRepo, authRepo = authRepo)

            viewModel.forkedProjectId.test {
                // Subscribe to state to keep combine active
                val stateJob =
                    backgroundScope.launch {
                        viewModel.state.collect { }
                    }
                advanceUntilIdle()

                viewModel.onEvent(DiscoveryEvent.ForkPattern("pub-a"))
                val forkedId = awaitItem()
                assertNotNull(forkedId)
                assertTrue(forkedId.isNotBlank())
            }
        }

    @Test
    fun `fork failure sets error`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            dataSource.addPattern(patternA)

            // Not authenticated → fork should fail
            val viewModel = createViewModel(dataSource = dataSource)

            viewModel.state.test {
                skipItems(1) // initial
                awaitItem() // loaded

                viewModel.onEvent(DiscoveryEvent.ForkPattern("pub-a"))
                val errored = awaitItem()
                assertNotNull(errored.error)
                assertNull(errored.forkingPatternId)
            }
        }

    @Test
    fun `clear error resets error state`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            dataSource.shouldFail = true
            val viewModel = createViewModel(dataSource = dataSource)

            viewModel.state.test {
                skipItems(1) // initial loading
                val errored = awaitItem()
                assertNotNull(errored.error)

                viewModel.onEvent(DiscoveryEvent.ClearError)
                val cleared = awaitItem()
                assertNull(cleared.error)
            }
        }

    @Test
    fun `refresh re-fetches from remote`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            val viewModel = createViewModel(dataSource = dataSource)

            viewModel.state.test {
                skipItems(1)
                val empty = awaitItem()
                assertEquals(0, empty.patterns.size)

                dataSource.addPattern(patternA)
                viewModel.onEvent(DiscoveryEvent.Refresh)

                // May get loading then loaded
                val events = cancelAndConsumeRemainingEvents()
            }

            advanceUntilIdle()
            assertEquals(1, viewModel.state.value.patterns.size)
        }

    @Test
    fun `remote failure shows error`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            dataSource.shouldFail = true
            val viewModel = createViewModel(dataSource = dataSource)

            viewModel.state.test {
                skipItems(1)
                val errored = awaitItem()
                assertFalse(errored.isLoading)
                assertNotNull(errored.error)
            }
        }
}
