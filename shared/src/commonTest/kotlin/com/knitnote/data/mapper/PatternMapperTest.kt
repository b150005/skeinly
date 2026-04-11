package com.knitnote.data.mapper

import com.knitnote.domain.model.Difficulty
import com.knitnote.domain.model.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PatternMapperTest {
    @Test
    fun `chartImageUrls empty list maps to null db string`() {
        val result = emptyList<String>().toChartImageUrlsDbString()
        assertNull(result)
    }

    @Test
    fun `chartImageUrls list maps to JSON string`() {
        val urls = listOf("user/pattern/a.jpg", "user/pattern/b.jpg")
        val result = urls.toChartImageUrlsDbString()
        assertEquals("""["user/pattern/a.jpg","user/pattern/b.jpg"]""", result)
    }

    @Test
    fun `chartImageUrls round-trips through JSON`() {
        val original = listOf("path/1.jpg", "path/2.jpg", "path/3.jpg")
        val json = original.toChartImageUrlsDbString()
        // Parse back using the same JSON approach
        val parsed = kotlinx.serialization.json.Json.decodeFromString<List<String>>(json!!)
        assertEquals(original, parsed)
    }

    @Test
    fun `chartImageUrls single item round-trips`() {
        val original = listOf("user-1/pattern-1/chart.jpg")
        val json = original.toChartImageUrlsDbString()
        val parsed = kotlinx.serialization.json.Json.decodeFromString<List<String>>(json!!)
        assertEquals(original, parsed)
    }

    @Test
    fun `Difficulty toDbString round-trips`() {
        Difficulty.entries.forEach { difficulty ->
            val dbString = difficulty.toDbString()
            val parsed =
                when (dbString) {
                    "beginner" -> Difficulty.BEGINNER
                    "intermediate" -> Difficulty.INTERMEDIATE
                    "advanced" -> Difficulty.ADVANCED
                    else -> error("Unknown")
                }
            assertEquals(difficulty, parsed)
        }
    }

    @Test
    fun `Visibility toDbString round-trips`() {
        Visibility.entries.forEach { visibility ->
            val dbString = visibility.toDbString()
            val parsed =
                when (dbString) {
                    "private" -> Visibility.PRIVATE
                    "shared" -> Visibility.SHARED
                    "public" -> Visibility.PUBLIC
                    else -> error("Unknown")
                }
            assertEquals(visibility, parsed)
        }
    }
}
