package io.github.b150005.knitnote.ui.patternedit

import app.cash.turbine.test
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Visibility
import io.github.b150005.knitnote.domain.usecase.CreatePatternUseCase
import io.github.b150005.knitnote.domain.usecase.FakeAuthRepository
import io.github.b150005.knitnote.domain.usecase.FakePatternRepository
import io.github.b150005.knitnote.domain.usecase.UpdatePatternUseCase
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock

@OptIn(ExperimentalCoroutinesApi::class)
class PatternEditViewModelTest {
    private val testDispatcher = StandardTestDispatcher()
    private val patternRepository = FakePatternRepository()
    private val authRepository = FakeAuthRepository()

    private fun createViewModel(patternId: String? = null): PatternEditViewModel {
        val createPattern = CreatePatternUseCase(patternRepository, authRepository)
        val updatePattern = UpdatePatternUseCase(patternRepository)
        return PatternEditViewModel(patternId, patternRepository, createPattern, updatePattern)
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
    fun `create mode initial state has null patternId`() =
        runTest {
            val viewModel = createViewModel(patternId = null)
            assertNull(viewModel.state.value.patternId)
            assertEquals("", viewModel.state.value.title)
        }

    @Test
    fun `create mode save calls createPattern`() =
        runTest {
            val viewModel = createViewModel(patternId = null)

            viewModel.onEvent(PatternEditEvent.UpdateTitle("New Scarf"))
            viewModel.onEvent(PatternEditEvent.UpdateDifficulty(Difficulty.BEGINNER))
            viewModel.onEvent(PatternEditEvent.UpdateGauge("20 sts"))
            viewModel.onEvent(PatternEditEvent.Save)
            advanceUntilIdle()

            val patterns = patternRepository.getByOwnerId("local-user")
            assertEquals(1, patterns.size)
            assertEquals("New Scarf", patterns[0].title)
            assertEquals("20 sts", patterns[0].gauge)
        }

    @Test
    fun `create mode blank title shows validation error`() =
        runTest {
            val viewModel = createViewModel(patternId = null)

            viewModel.onEvent(PatternEditEvent.Save)
            advanceUntilIdle()

            assertNotNull(viewModel.state.value.error)
            assertEquals("Title must not be blank", viewModel.state.value.error)
        }

    @Test
    fun `edit mode loads existing pattern on init`() =
        runTest {
            val now = Clock.System.now()
            val existing =
                Pattern(
                    id = "p1",
                    ownerId = "local-user",
                    title = "Existing Pattern",
                    description = "A warm scarf",
                    difficulty = Difficulty.INTERMEDIATE,
                    gauge = "18 sts",
                    yarnInfo = "DK weight",
                    needleSize = "US 6",
                    chartImageUrls = emptyList(),
                    visibility = Visibility.SHARED,
                    createdAt = now,
                    updatedAt = now,
                )
            patternRepository.create(existing)

            val viewModel = createViewModel(patternId = "p1")
            advanceUntilIdle()

            val state = viewModel.state.value
            assertEquals("Existing Pattern", state.title)
            assertEquals("A warm scarf", state.description)
            assertEquals(Difficulty.INTERMEDIATE, state.difficulty)
            assertEquals("18 sts", state.gauge)
            assertEquals("DK weight", state.yarnInfo)
            assertEquals("US 6", state.needleSize)
            assertEquals(Visibility.SHARED, state.visibility)
        }

    @Test
    fun `edit mode save calls updatePattern`() =
        runTest {
            val now = Clock.System.now()
            val existing =
                Pattern(
                    id = "p1",
                    ownerId = "local-user",
                    title = "Old Title",
                    description = null,
                    difficulty = null,
                    gauge = null,
                    yarnInfo = null,
                    needleSize = null,
                    chartImageUrls = emptyList(),
                    visibility = Visibility.PRIVATE,
                    createdAt = now,
                    updatedAt = now,
                )
            patternRepository.create(existing)

            val viewModel = createViewModel(patternId = "p1")
            advanceUntilIdle()

            viewModel.onEvent(PatternEditEvent.UpdateTitle("New Title"))
            viewModel.onEvent(PatternEditEvent.Save)
            advanceUntilIdle()

            val updated = patternRepository.getById("p1")
            assertEquals("New Title", updated?.title)
        }

    @Test
    fun `saveSuccess emits after successful save`() =
        runTest {
            val viewModel = createViewModel(patternId = null)

            viewModel.saveSuccess.test {
                viewModel.onEvent(PatternEditEvent.UpdateTitle("Test"))
                viewModel.onEvent(PatternEditEvent.Save)
                awaitItem() // Unit emitted on success
                assertFalse(viewModel.state.value.isSaving)
            }
        }
}
