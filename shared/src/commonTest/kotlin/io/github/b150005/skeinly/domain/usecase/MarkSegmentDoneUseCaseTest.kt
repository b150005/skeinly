package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.ProjectSegment
import io.github.b150005.skeinly.domain.model.SegmentState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class MarkSegmentDoneUseCaseTest {
    private class FixedClock(
        val fixed: Instant,
    ) : Clock {
        override fun now(): Instant = fixed
    }

    private val baseTime = Instant.parse("2026-04-24T12:00:00Z")

    @Test
    fun `long-press on absent row creates done directly`() =
        runTest {
            val repo = FakeProjectSegmentRepository()
            val useCase = MarkSegmentDoneUseCase(repo, FakeAuthRepository(), FixedClock(baseTime))

            val result = useCase("proj-1", "L1", 2, 2)

            assertTrue(result is UseCaseResult.Success)
            val seg = repo.getById(ProjectSegment.buildId("proj-1", "L1", 2, 2))
            assertNotNull(seg)
            assertEquals(SegmentState.DONE, seg.state)
        }

    @Test
    fun `long-press on wip upgrades to done`() =
        runTest {
            val repo = FakeProjectSegmentRepository()
            val id = ProjectSegment.buildId("proj-1", "L1", 2, 2)
            repo.seed(
                ProjectSegment(
                    id = id,
                    projectId = "proj-1",
                    layerId = "L1",
                    cellX = 2,
                    cellY = 2,
                    state = SegmentState.WIP,
                    ownerId = "u",
                    updatedAt = baseTime,
                ),
            )
            val useCase = MarkSegmentDoneUseCase(repo, FakeAuthRepository(), FixedClock(baseTime))

            useCase("proj-1", "L1", 2, 2)

            val seg = repo.getById(id)
            assertNotNull(seg)
            assertEquals(SegmentState.DONE, seg.state)
        }

    @Test
    fun `long-press on done is idempotent`() =
        runTest {
            val repo = FakeProjectSegmentRepository()
            val id = ProjectSegment.buildId("proj-1", "L1", 2, 2)
            repo.seed(
                ProjectSegment(
                    id = id,
                    projectId = "proj-1",
                    layerId = "L1",
                    cellX = 2,
                    cellY = 2,
                    state = SegmentState.DONE,
                    ownerId = "u",
                    updatedAt = baseTime,
                ),
            )
            val useCase = MarkSegmentDoneUseCase(repo, FakeAuthRepository(), FixedClock(baseTime))

            val result = useCase("proj-1", "L1", 2, 2)

            assertTrue(result is UseCaseResult.Success)
            val seg = repo.getById(id)
            assertNotNull(seg)
            assertEquals(SegmentState.DONE, seg.state)
        }
}
