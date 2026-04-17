package io.github.b150005.knitnote.data.mapper

import io.github.b150005.knitnote.db.ProgressEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ProgressMapperTest {
    private val now = "2024-06-15T10:30:00Z"

    private fun entity(
        note: String? = "row note",
        photoUrl: String? = null,
    ) = ProgressEntity(
        id = "prog1",
        project_id = "proj1",
        row_number = 5L,
        photo_url = photoUrl,
        note = note,
        created_at = now,
        owner_id = "owner-1",
    )

    @Test
    fun `toDomain maps all fields correctly`() {
        val progress = entity().toDomain()
        assertEquals("prog1", progress.id)
        assertEquals("proj1", progress.projectId)
        assertEquals(5, progress.rowNumber)
        assertNull(progress.photoUrl)
        assertEquals("row note", progress.note)
        assertEquals(Instant.parse(now), progress.createdAt)
    }

    @Test
    fun `toDomain maps null note to empty string`() {
        val progress = entity(note = null).toDomain()
        assertEquals("", progress.note)
    }

    @Test
    fun `toDomain maps photoUrl when present`() {
        val progress = entity(photoUrl = "https://example.com/photo.jpg").toDomain()
        assertEquals("https://example.com/photo.jpg", progress.photoUrl)
    }
}
