package io.github.b150005.knitnote.domain.model

import io.github.b150005.knitnote.testJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ProgressTest {
    private val json = testJson

    private val now = Instant.parse("2026-01-15T10:30:00Z")

    @Test
    fun `create progress with all fields`() {
        val progress =
            Progress(
                id = "prog-001",
                projectId = "proj-001",
                rowNumber = 42,
                photoUrl = "https://storage.example.com/progress1.jpg",
                note = "Finished the cable section",
                createdAt = now,
            )

        assertEquals("prog-001", progress.id)
        assertEquals("proj-001", progress.projectId)
        assertEquals(42, progress.rowNumber)
        assertEquals("https://storage.example.com/progress1.jpg", progress.photoUrl)
        assertEquals("Finished the cable section", progress.note)
    }

    @Test
    fun `create progress with optional fields`() {
        val progress =
            Progress(
                id = "prog-002",
                projectId = "proj-001",
                rowNumber = 1,
                photoUrl = null,
                note = "",
                createdAt = now,
            )

        assertNull(progress.photoUrl)
        assertEquals("", progress.note)
    }

    @Test
    fun `serialize and deserialize progress round-trip`() {
        val progress =
            Progress(
                id = "prog-001",
                projectId = "proj-001",
                rowNumber = 42,
                photoUrl = "https://storage.example.com/progress1.jpg",
                note = "Finished the cable section",
                createdAt = now,
            )

        val encoded = json.encodeToString(Progress.serializer(), progress)
        val decoded = json.decodeFromString(Progress.serializer(), encoded)
        assertEquals(progress, decoded)
    }
}
