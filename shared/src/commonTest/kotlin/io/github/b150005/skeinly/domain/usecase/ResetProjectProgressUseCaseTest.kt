package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.ProjectSegment
import io.github.b150005.skeinly.domain.model.SegmentState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

class ResetProjectProgressUseCaseTest {
    private val now = Instant.parse("2026-04-24T12:00:00Z")

    private fun seg(
        projectId: String,
        x: Int,
        y: Int,
        state: SegmentState = SegmentState.WIP,
    ) = ProjectSegment(
        id = ProjectSegment.buildId(projectId, "L1", x, y),
        projectId = projectId,
        layerId = "L1",
        cellX = x,
        cellY = y,
        state = state,
        ownerId = "u",
        updatedAt = now,
    )

    @Test
    fun `reset clears all segments for the project`() =
        runTest {
            val repo = FakeProjectSegmentRepository()
            repo.seed(seg("proj-1", 0, 0))
            repo.seed(seg("proj-1", 1, 0, SegmentState.DONE))
            repo.seed(seg("proj-2", 0, 0))

            val result = ResetProjectProgressUseCase(repo)("proj-1")

            assertTrue(result is UseCaseResult.Success)
            assertEquals(0, repo.getByProjectId("proj-1").size)
            // Other projects' segments untouched
            assertEquals(1, repo.getByProjectId("proj-2").size)
        }

    @Test
    fun `reset records call on repository`() =
        runTest {
            val repo = FakeProjectSegmentRepository()
            repo.seed(seg("proj-9", 0, 0))

            ResetProjectProgressUseCase(repo)("proj-9")

            assertEquals(listOf("proj-9"), repo.resetProjectCalls)
        }

    @Test
    fun `reset on project with no segments succeeds`() =
        runTest {
            val repo = FakeProjectSegmentRepository()

            val result = ResetProjectProgressUseCase(repo)("proj-empty")

            assertTrue(result is UseCaseResult.Success)
        }
}
