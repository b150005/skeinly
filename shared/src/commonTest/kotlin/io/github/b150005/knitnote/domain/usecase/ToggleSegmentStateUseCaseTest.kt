package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.ProjectSegment
import io.github.b150005.knitnote.domain.model.SegmentState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class ToggleSegmentStateUseCaseTest {
    private class FixedClock(
        var now: Instant,
    ) : Clock {
        override fun now(): Instant = now
    }

    private val baseTime = Instant.parse("2026-04-24T12:00:00Z")

    private fun useCase(
        repo: FakeProjectSegmentRepository,
        auth: FakeAuthRepository = FakeAuthRepository(),
        clock: FixedClock = FixedClock(baseTime),
    ) = ToggleSegmentStateUseCase(repo, auth, clock)

    @Test
    fun `tap on absent row inserts wip`() =
        runTest {
            val repo = FakeProjectSegmentRepository()
            val result = useCase(repo)("proj-1", "L1", 3, 5)

            assertTrue(result is UseCaseResult.Success)
            val seg = repo.getById(ProjectSegment.buildId("proj-1", "L1", 3, 5))
            assertNotNull(seg)
            assertEquals(SegmentState.WIP, seg.state)
            assertEquals(baseTime, seg.updatedAt)
        }

    @Test
    fun `tap on wip upgrades to done`() =
        runTest {
            val repo = FakeProjectSegmentRepository()
            repo.seed(
                ProjectSegment(
                    id = ProjectSegment.buildId("proj-1", "L1", 3, 5),
                    projectId = "proj-1",
                    layerId = "L1",
                    cellX = 3,
                    cellY = 5,
                    state = SegmentState.WIP,
                    ownerId = "u",
                    updatedAt = baseTime,
                ),
            )
            val laterClock = FixedClock(Instant.parse("2026-04-24T12:00:05Z"))
            val result = useCase(repo, clock = laterClock)("proj-1", "L1", 3, 5)

            assertTrue(result is UseCaseResult.Success)
            val seg = repo.getById(ProjectSegment.buildId("proj-1", "L1", 3, 5))
            assertNotNull(seg)
            assertEquals(SegmentState.DONE, seg.state)
            assertEquals(laterClock.now, seg.updatedAt)
        }

    @Test
    fun `tap on done deletes row back to todo`() =
        runTest {
            val repo = FakeProjectSegmentRepository()
            val id = ProjectSegment.buildId("proj-1", "L1", 3, 5)
            repo.seed(
                ProjectSegment(
                    id = id,
                    projectId = "proj-1",
                    layerId = "L1",
                    cellX = 3,
                    cellY = 5,
                    state = SegmentState.DONE,
                    ownerId = "u",
                    updatedAt = baseTime,
                ),
            )
            val result = useCase(repo)("proj-1", "L1", 3, 5)

            assertTrue(result is UseCaseResult.Success)
            assertNull(repo.getById(id))
        }

    @Test
    fun `new wip uses auth user id when authenticated`() =
        runTest {
            val repo = FakeProjectSegmentRepository()
            val auth = FakeAuthRepository()
            auth.signInWithEmail("u@example.com", "password")
            useCase(repo, auth = auth)("proj-1", "L1", 0, 0)

            val seg = repo.getById(ProjectSegment.buildId("proj-1", "L1", 0, 0))
            assertNotNull(seg)
            assertEquals("test-user-id", seg.ownerId)
        }

    @Test
    fun `new wip falls back to local user when unauthenticated`() =
        runTest {
            val repo = FakeProjectSegmentRepository()
            useCase(repo)("proj-1", "L1", 0, 0)

            val seg = repo.getById(ProjectSegment.buildId("proj-1", "L1", 0, 0))
            assertNotNull(seg)
            assertEquals("local-user", seg.ownerId)
        }
}
