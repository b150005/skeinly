package io.github.b150005.knitnote.data.mapper

import io.github.b150005.knitnote.db.PatternEntity
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Visibility
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatternMapperTest {
    private val now = "2024-06-15T10:30:00Z"

    private fun entity(
        difficulty: String? = "beginner",
        chartImageUrls: String? = null,
        visibility: String = "private",
        gauge: String? = null,
        yarnInfo: String? = null,
        needleSize: String? = null,
        parentPatternId: String? = null,
    ) = PatternEntity(
        id = "pat1",
        owner_id = "owner1",
        title = "Test Pattern",
        description = "A test",
        difficulty = difficulty,
        gauge = gauge,
        yarn_info = yarnInfo,
        needle_size = needleSize,
        chart_image_urls = chartImageUrls,
        visibility = visibility,
        created_at = now,
        updated_at = now,
        parent_pattern_id = parentPatternId,
    )

    @Test
    fun `toDomain maps null chartImageUrls to empty list`() {
        val pattern = entity(chartImageUrls = null).toDomain()
        assertTrue(pattern.chartImageUrls.isEmpty())
    }

    @Test
    fun `toDomain maps blank chartImageUrls to empty list`() {
        val pattern = entity(chartImageUrls = "  ").toDomain()
        assertTrue(pattern.chartImageUrls.isEmpty())
    }

    @Test
    fun `toDomain maps malformed JSON chartImageUrls to empty list`() {
        val pattern = entity(chartImageUrls = "not json").toDomain()
        assertTrue(pattern.chartImageUrls.isEmpty())
    }

    @Test
    fun `toDomain maps valid JSON chartImageUrls`() {
        val pattern = entity(chartImageUrls = """["a.jpg","b.jpg"]""").toDomain()
        assertEquals(listOf("a.jpg", "b.jpg"), pattern.chartImageUrls)
    }

    @Test
    fun `toDomain maps null difficulty to null`() {
        val pattern = entity(difficulty = null).toDomain()
        assertNull(pattern.difficulty)
    }

    @Test
    fun `toDomain throws on unknown difficulty`() {
        assertFailsWith<IllegalStateException> {
            entity(difficulty = "expert").toDomain()
        }
    }

    @Test
    fun `toDomain throws on unknown visibility`() {
        assertFailsWith<IllegalStateException> {
            entity(visibility = "hidden").toDomain()
        }
    }

    @Test
    fun `toDomain maps all difficulty values`() {
        assertEquals(Difficulty.BEGINNER, entity(difficulty = "beginner").toDomain().difficulty)
        assertEquals(Difficulty.INTERMEDIATE, entity(difficulty = "intermediate").toDomain().difficulty)
        assertEquals(Difficulty.ADVANCED, entity(difficulty = "advanced").toDomain().difficulty)
    }

    @Test
    fun `toDomain maps all visibility values`() {
        assertEquals(Visibility.PRIVATE, entity(visibility = "private").toDomain().visibility)
        assertEquals(Visibility.SHARED, entity(visibility = "shared").toDomain().visibility)
        assertEquals(Visibility.PUBLIC, entity(visibility = "public").toDomain().visibility)
    }

    @Test
    fun `toDomain maps gauge yarn_info needle_size`() {
        val pattern = entity(gauge = "20 sts", yarnInfo = "Worsted", needleSize = "US 7").toDomain()
        assertEquals("20 sts", pattern.gauge)
        assertEquals("Worsted", pattern.yarnInfo)
        assertEquals("US 7", pattern.needleSize)
    }

    @Test
    fun `toDomain maps null metadata to null`() {
        val pattern = entity().toDomain()
        assertNull(pattern.gauge)
        assertNull(pattern.yarnInfo)
        assertNull(pattern.needleSize)
    }

    @Test
    fun `toDomain maps null parent_pattern_id to null`() {
        val pattern = entity(parentPatternId = null).toDomain()
        assertNull(pattern.parentPatternId)
    }

    @Test
    fun `toDomain maps non-null parent_pattern_id`() {
        val pattern = entity(parentPatternId = "src-pat-42").toDomain()
        assertEquals("src-pat-42", pattern.parentPatternId)
    }

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
        val parsed =
            kotlinx.serialization.json.Json
                .decodeFromString<List<String>>(json!!)
        assertEquals(original, parsed)
    }

    @Test
    fun `chartImageUrls single item round-trips`() {
        val original = listOf("user-1/pattern-1/chart.jpg")
        val json = original.toChartImageUrlsDbString()
        val parsed =
            kotlinx.serialization.json.Json
                .decodeFromString<List<String>>(json!!)
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
