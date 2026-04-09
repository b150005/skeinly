package com.knitnote.domain.model

import kotlin.time.Instant
import com.knitnote.testJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectTest {

    private val json = testJson

    private val now = Instant.parse("2026-01-15T10:30:00Z")

    @Test
    fun `create project with all fields`() {
        val project = Project(
            id = "proj-001",
            ownerId = "user-001",
            patternId = "pat-001",
            title = "My Scarf Project",
            status = ProjectStatus.IN_PROGRESS,
            currentRow = 42,
            totalRows = 200,
            startedAt = now,
            completedAt = null,
            createdAt = now,
            updatedAt = now,
        )

        assertEquals("proj-001", project.id)
        assertEquals(ProjectStatus.IN_PROGRESS, project.status)
        assertEquals(42, project.currentRow)
        assertEquals(200, project.totalRows)
        assertNull(project.completedAt)
    }

    @Test
    fun `create project with minimal fields`() {
        val project = Project(
            id = "proj-002",
            ownerId = "user-001",
            patternId = "pat-001",
            title = "New Project",
            status = ProjectStatus.NOT_STARTED,
            currentRow = 0,
            totalRows = null,
            startedAt = null,
            completedAt = null,
            createdAt = now,
            updatedAt = now,
        )

        assertNull(project.totalRows)
        assertNull(project.startedAt)
    }

    @Test
    fun `serialize and deserialize project round-trip`() {
        val project = Project(
            id = "proj-001",
            ownerId = "user-001",
            patternId = "pat-001",
            title = "My Scarf Project",
            status = ProjectStatus.COMPLETED,
            currentRow = 200,
            totalRows = 200,
            startedAt = now,
            completedAt = Instant.parse("2026-02-15T10:30:00Z"),
            createdAt = now,
            updatedAt = now,
        )

        val encoded = json.encodeToString(Project.serializer(), project)
        val decoded = json.decodeFromString(Project.serializer(), encoded)
        assertEquals(project, decoded)
    }

    @Test
    fun `project status enum serialization`() {
        assertEquals("not_started", json.encodeToString(ProjectStatus.serializer(), ProjectStatus.NOT_STARTED).trim('"'))
        assertEquals("in_progress", json.encodeToString(ProjectStatus.serializer(), ProjectStatus.IN_PROGRESS).trim('"'))
        assertEquals("completed", json.encodeToString(ProjectStatus.serializer(), ProjectStatus.COMPLETED).trim('"'))
    }
}
