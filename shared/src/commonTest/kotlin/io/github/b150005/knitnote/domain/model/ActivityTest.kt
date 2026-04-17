package io.github.b150005.knitnote.domain.model

import io.github.b150005.knitnote.testJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class ActivityTest {
    private val json = testJson

    private val now = Instant.parse("2026-01-15T10:30:00Z")

    @Test
    fun `create activity with metadata`() {
        val activity =
            Activity(
                id = "act-001",
                userId = "user-001",
                type = ActivityType.SHARED,
                targetType = ActivityTargetType.PATTERN,
                targetId = "pat-001",
                metadata = """{"recipient": "user-002"}""",
                createdAt = now,
            )

        assertEquals("act-001", activity.id)
        assertEquals(ActivityType.SHARED, activity.type)
        assertEquals(ActivityTargetType.PATTERN, activity.targetType)
        assertEquals("""{"recipient": "user-002"}""", activity.metadata)
    }

    @Test
    fun `create activity without metadata`() {
        val activity =
            Activity(
                id = "act-002",
                userId = "user-001",
                type = ActivityType.COMPLETED,
                targetType = ActivityTargetType.PROJECT,
                targetId = "proj-001",
                metadata = null,
                createdAt = now,
            )

        assertNull(activity.metadata)
        assertEquals(ActivityType.COMPLETED, activity.type)
    }

    @Test
    fun `serialize and deserialize activity round-trip`() {
        val activity =
            Activity(
                id = "act-001",
                userId = "user-001",
                type = ActivityType.COMMENTED,
                targetType = ActivityTargetType.PATTERN,
                targetId = "pat-001",
                metadata = """{"preview": "Nice work!"}""",
                createdAt = now,
            )

        val encoded = json.encodeToString(Activity.serializer(), activity)
        val decoded = json.decodeFromString(Activity.serializer(), encoded)
        assertEquals(activity, decoded)
    }

    @Test
    fun `activity type enum serialization`() {
        assertEquals("shared", json.encodeToString(ActivityType.serializer(), ActivityType.SHARED).trim('"'))
        assertEquals("commented", json.encodeToString(ActivityType.serializer(), ActivityType.COMMENTED).trim('"'))
        assertEquals("forked", json.encodeToString(ActivityType.serializer(), ActivityType.FORKED).trim('"'))
        assertEquals("completed", json.encodeToString(ActivityType.serializer(), ActivityType.COMPLETED).trim('"'))
        assertEquals("started", json.encodeToString(ActivityType.serializer(), ActivityType.STARTED).trim('"'))
    }

    @Test
    fun `activity target type enum serialization`() {
        assertEquals("pattern", json.encodeToString(ActivityTargetType.serializer(), ActivityTargetType.PATTERN).trim('"'))
        assertEquals("project", json.encodeToString(ActivityTargetType.serializer(), ActivityTargetType.PROJECT).trim('"'))
    }
}
