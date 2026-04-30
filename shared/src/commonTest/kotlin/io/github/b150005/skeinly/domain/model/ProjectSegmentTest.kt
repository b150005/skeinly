package io.github.b150005.skeinly.domain.model

import io.github.b150005.skeinly.testJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Instant

class ProjectSegmentTest {
    private val json = testJson
    private val now = Instant.parse("2026-04-24T10:30:00Z")

    @Test
    fun `deterministic id follows seg project layer x y format`() {
        val id = ProjectSegment.buildId(projectId = "proj-1", layerId = "L1", cellX = 3, cellY = 5)
        assertEquals("seg:proj-1:L1:3:5", id)
    }

    @Test
    fun `deterministic id is stable across calls`() {
        val a = ProjectSegment.buildId("proj-1", "L1", 3, 5)
        val b = ProjectSegment.buildId("proj-1", "L1", 3, 5)
        assertEquals(a, b)
    }

    @Test
    fun `deterministic id differs for different coordinates`() {
        val a = ProjectSegment.buildId("proj-1", "L1", 3, 5)
        val b = ProjectSegment.buildId("proj-1", "L1", 5, 3)
        assertNotEquals(a, b)
    }

    @Test
    fun `deterministic id differs for different layers`() {
        val a = ProjectSegment.buildId("proj-1", "L1", 3, 5)
        val b = ProjectSegment.buildId("proj-1", "L2", 3, 5)
        assertNotEquals(a, b)
    }

    @Test
    fun `deterministic id differs for different projects`() {
        val a = ProjectSegment.buildId("proj-1", "L1", 3, 5)
        val b = ProjectSegment.buildId("proj-2", "L1", 3, 5)
        assertNotEquals(a, b)
    }

    @Test
    fun `deterministic id supports negative coordinates`() {
        val id = ProjectSegment.buildId("proj-1", "L1", -2, -7)
        assertEquals("seg:proj-1:L1:-2:-7", id)
    }

    @Test
    fun `create segment with all fields`() {
        val seg =
            ProjectSegment(
                id = "seg:proj-1:L1:3:5",
                projectId = "proj-1",
                layerId = "L1",
                cellX = 3,
                cellY = 5,
                state = SegmentState.WIP,
                ownerId = "user-1",
                updatedAt = now,
            )

        assertEquals("seg:proj-1:L1:3:5", seg.id)
        assertEquals(SegmentState.WIP, seg.state)
        assertEquals(3, seg.cellX)
        assertEquals(5, seg.cellY)
    }

    @Test
    fun `serialize round-trip preserves fields`() {
        val seg =
            ProjectSegment(
                id = "seg:proj-1:L1:3:5",
                projectId = "proj-1",
                layerId = "L1",
                cellX = 3,
                cellY = 5,
                state = SegmentState.DONE,
                ownerId = "user-1",
                updatedAt = now,
            )

        val encoded = json.encodeToString(ProjectSegment.serializer(), seg)
        val decoded = json.decodeFromString(ProjectSegment.serializer(), encoded)
        assertEquals(seg, decoded)
    }

    @Test
    fun `serialize uses snake_case wire names`() {
        val seg =
            ProjectSegment(
                id = "seg:proj-1:L1:3:5",
                projectId = "proj-1",
                layerId = "L1",
                cellX = 3,
                cellY = 5,
                state = SegmentState.WIP,
                ownerId = "user-1",
                updatedAt = now,
            )

        val encoded = json.encodeToString(ProjectSegment.serializer(), seg)
        assertEquals(true, encoded.contains("\"project_id\""))
        assertEquals(true, encoded.contains("\"layer_id\""))
        assertEquals(true, encoded.contains("\"cell_x\""))
        assertEquals(true, encoded.contains("\"cell_y\""))
        assertEquals(true, encoded.contains("\"owner_id\""))
        assertEquals(true, encoded.contains("\"updated_at\""))
    }

    @Test
    fun `segment state wire value is lowercase`() {
        val wip =
            ProjectSegment(
                id = "seg:proj-1:L1:0:0",
                projectId = "proj-1",
                layerId = "L1",
                cellX = 0,
                cellY = 0,
                state = SegmentState.WIP,
                ownerId = "user-1",
                updatedAt = now,
            )
        val encoded = json.encodeToString(ProjectSegment.serializer(), wip)
        assertEquals(true, encoded.contains("\"wip\""))
    }

    @Test
    fun `segment state decodes done`() {
        val payload =
            """
            {"id":"seg:p:L1:0:0","project_id":"p","layer_id":"L1","cell_x":0,"cell_y":0,"state":"done","owner_id":"u","updated_at":"2026-04-24T10:30:00Z"}
            """.trimIndent()
        val decoded = json.decodeFromString(ProjectSegment.serializer(), payload)
        assertEquals(SegmentState.DONE, decoded.state)
    }
}
