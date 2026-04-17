package io.github.b150005.knitnote.data.mapper

import io.github.b150005.knitnote.db.ProjectEntity
import io.github.b150005.knitnote.domain.model.ProjectStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class ProjectMapperTest {
    private val now = "2024-06-15T10:30:00Z"

    private fun entity(
        status: String = "in_progress",
        totalRows: Long? = 100L,
        startedAt: String? = now,
        completedAt: String? = null,
    ) = ProjectEntity(
        id = "p1",
        owner_id = "owner1",
        pattern_id = "pat1",
        title = "Test Project",
        status = status,
        current_row = 42L,
        total_rows = totalRows,
        started_at = startedAt,
        completed_at = completedAt,
        created_at = now,
        updated_at = now,
    )

    @Test
    fun `toDomain maps all fields correctly`() {
        val project = entity().toDomain()
        assertEquals("p1", project.id)
        assertEquals("owner1", project.ownerId)
        assertEquals("pat1", project.patternId)
        assertEquals("Test Project", project.title)
        assertEquals(ProjectStatus.IN_PROGRESS, project.status)
        assertEquals(42, project.currentRow)
        assertEquals(100, project.totalRows)
        assertEquals(Instant.parse(now), project.startedAt)
        assertNull(project.completedAt)
        assertEquals(Instant.parse(now), project.createdAt)
        assertEquals(Instant.parse(now), project.updatedAt)
    }

    @Test
    fun `toDomain maps null totalRows to null`() {
        val project = entity(totalRows = null).toDomain()
        assertNull(project.totalRows)
    }

    @Test
    fun `toDomain maps null startedAt to null`() {
        val project = entity(startedAt = null).toDomain()
        assertNull(project.startedAt)
    }

    @Test
    fun `toDomain maps completedAt when present`() {
        val project = entity(completedAt = now).toDomain()
        assertEquals(Instant.parse(now), project.completedAt)
    }

    @Test
    fun `toDomain maps not_started status`() {
        val project = entity(status = "not_started").toDomain()
        assertEquals(ProjectStatus.NOT_STARTED, project.status)
    }

    @Test
    fun `toDomain maps completed status`() {
        val project = entity(status = "completed").toDomain()
        assertEquals(ProjectStatus.COMPLETED, project.status)
    }

    @Test
    fun `toDomain throws on unknown status`() {
        assertFailsWith<IllegalStateException> {
            entity(status = "invalid").toDomain()
        }
    }

    @Test
    fun `toDbString round-trips all statuses`() {
        ProjectStatus.entries.forEach { status ->
            val dbString = status.toDbString()
            val roundTripped = entity(status = dbString).toDomain().status
            assertEquals(status, roundTripped)
        }
    }
}
