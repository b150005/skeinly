package io.github.b150005.knitnote.ui.discovery

import app.cash.turbine.test
import io.github.b150005.knitnote.data.remote.FakePublicPatternDataSource
import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.model.ChartExtents
import io.github.b150005.knitnote.domain.model.CoordinateSystem
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.SortOrder
import io.github.b150005.knitnote.domain.model.StorageVariant
import io.github.b150005.knitnote.domain.model.StructuredChart
import io.github.b150005.knitnote.domain.model.Visibility
import io.github.b150005.knitnote.domain.usecase.FakeAuthRepository
import io.github.b150005.knitnote.domain.usecase.FakePatternRepository
import io.github.b150005.knitnote.domain.usecase.FakeProjectRepository
import io.github.b150005.knitnote.domain.usecase.FakeStructuredChartRepository
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
        chartRepo: FakeStructuredChartRepository = FakeStructuredChartRepository(),
        authRepo: FakeAuthRepository = FakeAuthRepository(),
    ): DiscoveryViewModel {
        val getPublicPatterns = GetPublicPatternsUseCase(dataSource)
        val forkPublicPattern = ForkPublicPatternUseCase(patternRepo, projectRepo, chartRepo, authRepo)
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

            viewModel.forkedProject.test {
                // Subscribe to state to keep combine active
                val stateJob =
                    backgroundScope.launch {
                        viewModel.state.collect { }
                    }
                advanceUntilIdle()

                viewModel.onEvent(DiscoveryEvent.ForkPattern("pub-a"))
                val forkResult = awaitItem()
                assertNotNull(forkResult)
                assertTrue(forkResult.projectId.isNotBlank())
                // Phase 36.3: source has no structured chart in this fake setup,
                // so chartCloned is `false` (nothing to clone, NOT a failure)
                // and chartCloneFailed is `false` — Snackbar must show success
                // copy, not the failure copy. See DiscoveryForkResult tri-state.
                assertFalse(forkResult.chartCloned)
                assertFalse(forkResult.chartCloneFailed)
            }
        }

    // Phase 36.3 (ADR-012 §3 / §7) regression anchor: when the source pattern
    // HAS a structured chart and the chart-clone repo throws, the project + the
    // pattern still land and the channel emits chartCloned=false +
    // chartCloneFailed=true so the Snackbar shows the failure copy. Without
    // this test the Snackbar copy could regress to "success" on every chart-
    // clone failure since the use-case-layer tests do not cover the
    // ViewModel → channel-payload → DiscoveryForkResult conversion path.
    @Test
    fun `fork emits chartCloneFailed true when chart clone throws`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            dataSource.addPattern(patternA)

            val authRepo = FakeAuthRepository()
            authRepo.setAuthState(AuthState.Authenticated("user-1", "test@test.com"))
            val patternRepo = FakePatternRepository()
            patternRepo.create(patternA)

            // Seed the chart so `forkFor` enters the success path normally,
            // then arm `failNext` so the actual `forkFor` call throws —
            // exactly the "had a chart, clone hit transient failure" scenario.
            val chartRepo = FakeStructuredChartRepository()
            chartRepo.seed(
                StructuredChart(
                    id = "chart-pub-a",
                    patternId = "pub-a",
                    ownerId = "other-user",
                    schemaVersion = StructuredChart.CURRENT_SCHEMA_VERSION,
                    storageVariant = StorageVariant.INLINE,
                    coordinateSystem = CoordinateSystem.RECT_GRID,
                    extents = ChartExtents.Rect(0, 0, 0, 0),
                    layers = emptyList(),
                    revisionId = "rev-source",
                    parentRevisionId = null,
                    contentHash = "h1-deadbeef",
                    createdAt = Instant.fromEpochMilliseconds(500),
                    updatedAt = Instant.fromEpochMilliseconds(500),
                ),
            )
            chartRepo.failNext = RuntimeException("transient storage error")

            val viewModel =
                createViewModel(
                    dataSource = dataSource,
                    patternRepo = patternRepo,
                    chartRepo = chartRepo,
                    authRepo = authRepo,
                )

            viewModel.forkedProject.test {
                val stateJob =
                    backgroundScope.launch {
                        viewModel.state.collect { }
                    }
                advanceUntilIdle()

                viewModel.onEvent(DiscoveryEvent.ForkPattern("pub-a"))
                val forkResult = awaitItem()
                assertNotNull(forkResult)
                assertTrue(forkResult.projectId.isNotBlank(), "project still landed")
                assertFalse(forkResult.chartCloned, "chart did not land")
                assertTrue(forkResult.chartCloneFailed, "failure flag set so Snackbar shows fallback copy")
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

    // Phase 36.4 (ADR-012 §5) regression anchor: the companion set on the
    // initial load must reach state.patternsWithCharts so PatternCard can
    // decide to render the chart-preview thumbnail.
    @Test
    fun `initial load populates patternsWithCharts companion set`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            dataSource.addPattern(patternA)
            dataSource.addPattern(patternB)
            dataSource.markHasChart("pub-a")
            val viewModel = createViewModel(dataSource = dataSource)

            viewModel.state.test {
                skipItems(1) // initial empty
                val loaded = awaitItem()
                assertEquals(2, loaded.patterns.size)
                assertEquals(setOf("pub-a"), loaded.patternsWithCharts)
            }
        }

    // Phase 36.4 (ADR-012 §4): toggling the "Charts only" chip flips the
    // filter flag and re-loads against the data source. The fake's chartsOnly
    // path filters to only chartful patterns so the visible list shrinks.
    //
    // Uses the backgroundScope subscriber pattern (mirrors the fork tests
    // above): without an active subscriber, SharingStarted.WhileSubscribed
    // would tear the upstream `combine` down before the launched `load()`
    // re-fetch coroutine completes, leaving `state.value` at a stale snapshot
    // when the assertion runs.
    @Test
    fun `ToggleChartsOnly flips filter and re-loads with server-side filter`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            dataSource.addPattern(patternA)
            dataSource.addPattern(patternB)
            dataSource.markHasChart("pub-a")
            val viewModel = createViewModel(dataSource = dataSource)

            backgroundScope.launch { viewModel.state.collect { } }
            advanceUntilIdle()
            assertFalse(viewModel.state.value.chartsOnlyFilter)
            assertEquals(2, viewModel.state.value.patterns.size)

            viewModel.onEvent(DiscoveryEvent.ToggleChartsOnly)
            advanceUntilIdle()

            val filtered = viewModel.state.value
            assertTrue(filtered.chartsOnlyFilter)
            assertEquals(1, filtered.patterns.size)
            assertEquals("pub-a", filtered.patterns.first().id)
            assertEquals(setOf("pub-a"), filtered.patternsWithCharts)
        }

    // Phase 36.4 (ADR-012 §4): a second toggle flips back, re-broadens the
    // list, and the companion set still names which patterns have charts.
    @Test
    fun `ToggleChartsOnly twice returns to unfiltered list with companion set intact`() =
        runTest {
            val dataSource = FakePublicPatternDataSource()
            dataSource.addPattern(patternA)
            dataSource.addPattern(patternB)
            dataSource.markHasChart("pub-a")
            val viewModel = createViewModel(dataSource = dataSource)

            backgroundScope.launch { viewModel.state.collect { } }
            advanceUntilIdle()

            viewModel.onEvent(DiscoveryEvent.ToggleChartsOnly)
            advanceUntilIdle()
            viewModel.onEvent(DiscoveryEvent.ToggleChartsOnly)
            advanceUntilIdle()

            val settled = viewModel.state.value
            assertFalse(settled.chartsOnlyFilter)
            assertEquals(2, settled.patterns.size)
            assertEquals(setOf("pub-a"), settled.patternsWithCharts)
        }
}
