package com.knitnote.domain.usecase

import com.knitnote.domain.model.Activity
import com.knitnote.domain.model.ActivityTargetType
import com.knitnote.domain.model.ActivityType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GetActivitiesUseCaseTest {

    private val now = Instant.fromEpochMilliseconds(1000)

    private fun makeActivity(
        id: String,
        userId: String = "user-1",
        type: ActivityType = ActivityType.STARTED,
        targetType: ActivityTargetType = ActivityTargetType.PROJECT,
        targetId: String = "proj-1",
    ) = Activity(
        id = id,
        userId = userId,
        type = type,
        targetType = targetType,
        targetId = targetId,
        metadata = null,
        createdAt = now,
    )

    @Test
    fun `returns failure when activity repository is null`() = runTest {
        val useCase = GetActivitiesUseCase(null)
        val result = useCase("user-1")
        assertIs<UseCaseResult.Failure>(result)
        assertIs<UseCaseError.Validation>(result.error)
    }

    @Test
    fun `returns activities for user`() = runTest {
        val repo = FakeActivityRepository()
        repo.addActivity(makeActivity("a-1"))
        repo.addActivity(makeActivity("a-2"))
        repo.addActivity(makeActivity("a-3", userId = "user-2"))
        val useCase = GetActivitiesUseCase(repo)

        val result = useCase("user-1")

        assertIs<UseCaseResult.Success<List<Activity>>>(result)
        assertEquals(2, result.value.size)
    }

    @Test
    fun `returns empty list when no activities`() = runTest {
        val useCase = GetActivitiesUseCase(FakeActivityRepository())

        val result = useCase("user-1")

        assertIs<UseCaseResult.Success<List<Activity>>>(result)
        assertTrue(result.value.isEmpty())
    }

    @Test
    fun `observe returns empty flow when repository is null`() = runTest {
        val useCase = GetActivitiesUseCase(null)
        val result = useCase.observe("user-1").first()
        assertTrue(result.isEmpty())
    }

    @Test
    fun `observe returns activities for user`() = runTest {
        val repo = FakeActivityRepository()
        repo.addActivity(makeActivity("a-1"))
        repo.addActivity(makeActivity("a-2", userId = "user-2"))
        val useCase = GetActivitiesUseCase(repo)

        val result = useCase.observe("user-1").first()

        assertEquals(1, result.size)
        assertEquals("a-1", result.first().id)
    }
}
