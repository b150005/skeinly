package com.knitnote.domain.usecase

import com.knitnote.domain.model.Activity
import com.knitnote.domain.model.ActivityTargetType
import com.knitnote.domain.model.ActivityType
import com.knitnote.domain.repository.ActivityRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreateActivityUseCaseTest {

    @Test
    fun `no-ops when activity repository is null`() = runTest {
        val useCase = CreateActivityUseCase(null)

        // Should not throw
        useCase(
            userId = "user-1",
            type = ActivityType.STARTED,
            targetType = ActivityTargetType.PROJECT,
            targetId = "proj-1",
        )
    }

    @Test
    fun `creates activity with correct fields`() = runTest {
        val repo = FakeActivityRepository()
        val useCase = CreateActivityUseCase(repo)

        useCase(
            userId = "user-1",
            type = ActivityType.COMMENTED,
            targetType = ActivityTargetType.PROJECT,
            targetId = "proj-1",
            metadata = "Great progress!",
        )

        val activities = repo.getByUserId("user-1")
        assertEquals(1, activities.size)
        val activity = activities.first()
        assertEquals("user-1", activity.userId)
        assertEquals(ActivityType.COMMENTED, activity.type)
        assertEquals(ActivityTargetType.PROJECT, activity.targetType)
        assertEquals("proj-1", activity.targetId)
        assertEquals("Great progress!", activity.metadata)
    }

    @Test
    fun `creates activity without metadata`() = runTest {
        val repo = FakeActivityRepository()
        val useCase = CreateActivityUseCase(repo)

        useCase(
            userId = "user-1",
            type = ActivityType.STARTED,
            targetType = ActivityTargetType.PROJECT,
            targetId = "proj-1",
        )

        val activity = repo.getByUserId("user-1").first()
        assertEquals(null, activity.metadata)
    }

    @Test
    fun `generates unique id for each activity`() = runTest {
        val repo = FakeActivityRepository()
        val useCase = CreateActivityUseCase(repo)

        useCase("user-1", ActivityType.STARTED, ActivityTargetType.PROJECT, "proj-1")
        useCase("user-1", ActivityType.COMPLETED, ActivityTargetType.PROJECT, "proj-1")

        val activities = repo.getByUserId("user-1")
        assertEquals(2, activities.size)
        assertTrue(activities[0].id != activities[1].id)
    }

    @Test
    fun `swallows exception from repository and does not propagate`() = runTest {
        val throwingRepo = object : ActivityRepository {
            override suspend fun getByUserId(userId: String) = emptyList<Activity>()
            override fun observeByUserId(userId: String): Flow<List<Activity>> = flowOf()
            override suspend fun create(activity: Activity): Activity {
                throw RuntimeException("Network failure")
            }
        }
        val useCase = CreateActivityUseCase(throwingRepo)

        // Should not throw — best-effort
        useCase("user-1", ActivityType.STARTED, ActivityTargetType.PROJECT, "proj-1")
    }
}
