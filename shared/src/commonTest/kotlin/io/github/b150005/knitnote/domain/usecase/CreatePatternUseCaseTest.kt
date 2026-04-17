package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.LocalUser
import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Visibility
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreatePatternUseCaseTest {
    private val patternRepository = FakePatternRepository()
    private val authRepository = FakeAuthRepository()
    private val useCase = CreatePatternUseCase(patternRepository, authRepository)

    @Test
    fun `creates pattern with all fields`() =
        runTest {
            authRepository.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))

            val result =
                useCase(
                    title = "Cable Knit Scarf",
                    description = "A warm cable knit scarf",
                    difficulty = Difficulty.INTERMEDIATE,
                    gauge = "20 sts = 4 in",
                    yarnInfo = "Worsted weight, 100% merino",
                    needleSize = "US 7 (4.5mm)",
                    visibility = Visibility.PRIVATE,
                )

            assertIs<UseCaseResult.Success<Pattern>>(result)
            val pattern = result.value
            assertEquals("Cable Knit Scarf", pattern.title)
            assertEquals("A warm cable knit scarf", pattern.description)
            assertEquals(Difficulty.INTERMEDIATE, pattern.difficulty)
            assertEquals("20 sts = 4 in", pattern.gauge)
            assertEquals("Worsted weight, 100% merino", pattern.yarnInfo)
            assertEquals("US 7 (4.5mm)", pattern.needleSize)
            assertEquals(Visibility.PRIVATE, pattern.visibility)
            assertEquals("user-1", pattern.ownerId)
            assertTrue(pattern.chartImageUrls.isEmpty())
        }

    @Test
    fun `creates pattern with only required fields`() =
        runTest {
            authRepository.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))

            val result = useCase(title = "Simple Scarf")

            assertIs<UseCaseResult.Success<Pattern>>(result)
            val pattern = result.value
            assertEquals("Simple Scarf", pattern.title)
            assertNull(pattern.description)
            assertNull(pattern.difficulty)
            assertNull(pattern.gauge)
            assertNull(pattern.yarnInfo)
            assertNull(pattern.needleSize)
        }

    @Test
    fun `blank title returns Validation error`() =
        runTest {
            val result = useCase(title = "   ")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `empty title returns Validation error`() =
        runTest {
            val result = useCase(title = "")
            assertIs<UseCaseResult.Failure>(result)
            assertIs<UseCaseError.Validation>(result.error)
        }

    @Test
    fun `blank optional fields are stored as null`() =
        runTest {
            authRepository.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))

            val result =
                useCase(
                    title = "Test",
                    description = "   ",
                    gauge = "",
                    yarnInfo = "  ",
                    needleSize = "",
                )

            assertIs<UseCaseResult.Success<Pattern>>(result)
            assertNull(result.value.description)
            assertNull(result.value.gauge)
            assertNull(result.value.yarnInfo)
            assertNull(result.value.needleSize)
        }

    @Test
    fun `pattern is persisted in repository`() =
        runTest {
            authRepository.setAuthState(AuthState.Authenticated("user-1", "test@example.com"))
            val result = useCase(title = "Persisted Pattern")

            assertIs<UseCaseResult.Success<Pattern>>(result)
            val retrieved = patternRepository.getById(result.value.id)
            assertNotNull(retrieved)
            assertEquals("Persisted Pattern", retrieved.title)
        }

    @Test
    fun `uses LocalUser ID when not authenticated`() =
        runTest {
            val result = useCase(title = "Local Pattern")

            assertIs<UseCaseResult.Success<Pattern>>(result)
            assertEquals(LocalUser.ID, result.value.ownerId)
        }
}
