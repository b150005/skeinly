package io.github.b150005.knitnote.ui.patternlibrary

import app.cash.turbine.test
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Visibility
import io.github.b150005.knitnote.domain.usecase.DeletePatternUseCase
import io.github.b150005.knitnote.domain.usecase.FakeAuthRepository
import io.github.b150005.knitnote.domain.usecase.FakePatternRepository
import io.github.b150005.knitnote.domain.usecase.GetPatternsUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class PatternLibraryViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val patternRepository = FakePatternRepository()
    private val authRepository = FakeAuthRepository()

    private fun createViewModel(): PatternLibraryViewModel {
        val getPatterns = GetPatternsUseCase(patternRepository, authRepository)
        val deletePattern = DeletePatternUseCase(patternRepository)
        return PatternLibraryViewModel(getPatterns, deletePattern)
    }

    private fun testPattern(
        id: String,
        title: String,
        difficulty: Difficulty? = null,
    ): Pattern {
        val now = Clock.System.now()
        return Pattern(
            id = id,
            ownerId = "local-user",
            title = title,
            description = null,
            difficulty = difficulty,
            gauge = null,
            yarnInfo = null,
            needleSize = null,
            chartImageUrls = emptyList(),
            visibility = Visibility.PRIVATE,
            createdAt = now,
            updatedAt = now,
        )
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has isLoading true`() =
        runTest {
            val viewModel = createViewModel()
            assertEquals(true, viewModel.state.value.isLoading)
        }

    @Test
    fun `patterns loaded from use case`() =
        runTest {
            patternRepository.create(testPattern("p1", "Scarf"))
            patternRepository.create(testPattern("p2", "Hat"))
            val viewModel = createViewModel()

            viewModel.state.test {
                skipItems(1) // initial
                val loaded = awaitItem()
                assertEquals(2, loaded.patterns.size)
                assertEquals(false, loaded.isLoading)
            }
        }

    @Test
    fun `search filters by title`() =
        runTest {
            patternRepository.create(testPattern("p1", "Cable Scarf"))
            patternRepository.create(testPattern("p2", "Simple Hat"))
            val viewModel = createViewModel()

            viewModel.state.test {
                skipItems(1) // initial
                awaitItem() // loaded with 2 patterns

                viewModel.onEvent(PatternLibraryEvent.UpdateSearchQuery("scarf"))
                val filtered = awaitItem()
                assertEquals(1, filtered.patterns.size)
                assertEquals("Cable Scarf", filtered.patterns[0].title)
            }
        }

    @Test
    fun `difficulty filter works`() =
        runTest {
            patternRepository.create(testPattern("p1", "Easy Scarf", Difficulty.BEGINNER))
            patternRepository.create(testPattern("p2", "Complex Cable", Difficulty.ADVANCED))
            val viewModel = createViewModel()

            viewModel.state.test {
                skipItems(1) // initial
                awaitItem() // loaded with 2

                viewModel.onEvent(PatternLibraryEvent.UpdateDifficultyFilter(Difficulty.BEGINNER))
                val filtered = awaitItem()
                assertEquals(1, filtered.patterns.size)
                assertEquals("Easy Scarf", filtered.patterns[0].title)
            }
        }

    @Test
    fun `delete pattern removes from list`() =
        runTest {
            patternRepository.create(testPattern("p1", "Scarf"))
            val viewModel = createViewModel()

            viewModel.state.test {
                skipItems(1) // initial
                awaitItem() // loaded with 1

                viewModel.onEvent(PatternLibraryEvent.DeletePattern("p1"))
                advanceUntilIdle()
                val afterDelete = awaitItem()
                assertTrue(afterDelete.patterns.isEmpty())
            }
        }

    @Test
    fun `error is clearable`() =
        runTest {
            val viewModel = createViewModel()
            advanceUntilIdle()

            // Error is null in initial state and remains null after clear
            assertNull(viewModel.state.value.error)
            viewModel.onEvent(PatternLibraryEvent.ClearError)
            advanceUntilIdle()
            assertNull(viewModel.state.value.error)
        }
}
