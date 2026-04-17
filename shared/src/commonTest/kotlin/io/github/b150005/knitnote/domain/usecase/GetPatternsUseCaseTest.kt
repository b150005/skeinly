package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.LocalUser
import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Visibility
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class GetPatternsUseCaseTest {
    private val patternRepository = FakePatternRepository()
    private val authRepository = FakeAuthRepository()
    private val useCase = GetPatternsUseCase(patternRepository, authRepository)

    private fun testPattern(
        id: String = "p1",
        ownerId: String = "test-user-id",
        title: String = "Test Pattern",
    ): Pattern {
        val now = Clock.System.now()
        return Pattern(
            id = id,
            ownerId = ownerId,
            title = title,
            description = null,
            difficulty = Difficulty.BEGINNER,
            gauge = "20 sts = 4 in",
            yarnInfo = "Worsted weight",
            needleSize = "US 7",
            chartImageUrls = emptyList(),
            visibility = Visibility.PRIVATE,
            createdAt = now,
            updatedAt = now,
        )
    }

    @Test
    fun `emits patterns for current user`() =
        runTest {
            authRepository.setAuthState(AuthState.Authenticated("test-user-id", "test@example.com"))
            patternRepository.create(testPattern(id = "p1", ownerId = "test-user-id"))
            patternRepository.create(testPattern(id = "p2", ownerId = "other-user"))

            val patterns = useCase().first()
            assertEquals(1, patterns.size)
            assertEquals("p1", patterns[0].id)
        }

    @Test
    fun `uses LocalUser ID when not authenticated`() =
        runTest {
            patternRepository.create(testPattern(id = "p1", ownerId = LocalUser.ID))

            val patterns = useCase().first()
            assertEquals(1, patterns.size)
            assertEquals(LocalUser.ID, patterns[0].ownerId)
        }

    @Test
    fun `emits empty list when no patterns exist`() =
        runTest {
            authRepository.setAuthState(AuthState.Authenticated("test-user-id", "test@example.com"))

            val patterns = useCase().first()
            assertTrue(patterns.isEmpty())
        }
}
