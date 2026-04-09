package com.knitnote.domain.model

import kotlin.time.Instant
import com.knitnote.testJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PatternTest {

    private val json = testJson

    private val now = Instant.parse("2026-01-15T10:30:00Z")

    private fun fullPattern() = Pattern(
        id = "pat-001",
        ownerId = "user-001",
        title = "Cable Knit Scarf",
        description = "A warm scarf with cable pattern",
        difficulty = Difficulty.INTERMEDIATE,
        gauge = "20 stitches x 28 rows = 10cm",
        yarnInfo = "Worsted weight, 200g",
        needleSize = "5.0mm",
        chartImageUrls = listOf("https://storage.example.com/chart1.png", "https://storage.example.com/chart2.png"),
        visibility = Visibility.PUBLIC,
        createdAt = now,
        updatedAt = now,
    )

    @Test
    fun `create pattern with all fields`() {
        val pattern = fullPattern()

        assertEquals("pat-001", pattern.id)
        assertEquals("user-001", pattern.ownerId)
        assertEquals("Cable Knit Scarf", pattern.title)
        assertEquals(Difficulty.INTERMEDIATE, pattern.difficulty)
        assertEquals(Visibility.PUBLIC, pattern.visibility)
        assertEquals(2, pattern.chartImageUrls.size)
    }

    @Test
    fun `create pattern with nullable fields as null`() {
        val pattern = Pattern(
            id = "pat-002",
            ownerId = "user-001",
            title = "Quick Hat",
            description = null,
            difficulty = null,
            gauge = null,
            yarnInfo = null,
            needleSize = null,
            chartImageUrls = emptyList(),
            visibility = Visibility.PRIVATE,
            createdAt = now,
            updatedAt = now,
        )

        assertNull(pattern.description)
        assertNull(pattern.difficulty)
        assertNull(pattern.gauge)
        assertNull(pattern.yarnInfo)
        assertNull(pattern.needleSize)
    }

    @Test
    fun `serialize and deserialize pattern round-trip`() {
        val pattern = fullPattern()
        val encoded = json.encodeToString(Pattern.serializer(), pattern)
        val decoded = json.decodeFromString(Pattern.serializer(), encoded)
        assertEquals(pattern, decoded)
    }

    @Test
    fun `visibility enum serialization`() {
        assertEquals("private", json.encodeToString(Visibility.serializer(), Visibility.PRIVATE).trim('"'))
        assertEquals("shared", json.encodeToString(Visibility.serializer(), Visibility.SHARED).trim('"'))
        assertEquals("public", json.encodeToString(Visibility.serializer(), Visibility.PUBLIC).trim('"'))
    }

    @Test
    fun `difficulty enum serialization`() {
        assertEquals("beginner", json.encodeToString(Difficulty.serializer(), Difficulty.BEGINNER).trim('"'))
        assertEquals("intermediate", json.encodeToString(Difficulty.serializer(), Difficulty.INTERMEDIATE).trim('"'))
        assertEquals("advanced", json.encodeToString(Difficulty.serializer(), Difficulty.ADVANCED).trim('"'))
    }
}
