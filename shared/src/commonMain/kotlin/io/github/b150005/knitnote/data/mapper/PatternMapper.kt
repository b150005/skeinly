package io.github.b150005.knitnote.data.mapper

import io.github.b150005.knitnote.db.PatternEntity
import io.github.b150005.knitnote.domain.model.Difficulty
import io.github.b150005.knitnote.domain.model.Pattern
import io.github.b150005.knitnote.domain.model.Visibility
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.time.Instant

private val json = Json { ignoreUnknownKeys = true }
private val stringListSerializer = ListSerializer(String.serializer())

fun PatternEntity.toDomain(): Pattern =
    Pattern(
        id = id,
        ownerId = owner_id,
        title = title,
        description = description,
        difficulty = difficulty?.toDifficulty(),
        gauge = gauge,
        yarnInfo = yarn_info,
        needleSize = needle_size,
        chartImageUrls = chart_image_urls.toChartImageUrlsList(),
        visibility = visibility.toVisibility(),
        createdAt = Instant.parse(created_at),
        updatedAt = Instant.parse(updated_at),
    )

fun List<String>.toChartImageUrlsDbString(): String? = if (isEmpty()) null else json.encodeToString(stringListSerializer, this)

private fun String?.toChartImageUrlsList(): List<String> {
    if (isNullOrBlank()) return emptyList()
    return try {
        json.decodeFromString<List<String>>(this)
    } catch (_: Exception) {
        emptyList()
    }
}

private fun String.toDifficulty(): Difficulty =
    when (this) {
        "beginner" -> Difficulty.BEGINNER
        "intermediate" -> Difficulty.INTERMEDIATE
        "advanced" -> Difficulty.ADVANCED
        else -> throw IllegalStateException("Unknown Difficulty in database: '$this'")
    }

private fun String.toVisibility(): Visibility =
    when (this) {
        "private" -> Visibility.PRIVATE
        "shared" -> Visibility.SHARED
        "public" -> Visibility.PUBLIC
        else -> throw IllegalStateException("Unknown Visibility in database: '$this'")
    }

fun Difficulty.toDbString(): String =
    when (this) {
        Difficulty.BEGINNER -> "beginner"
        Difficulty.INTERMEDIATE -> "intermediate"
        Difficulty.ADVANCED -> "advanced"
    }

fun Visibility.toDbString(): String =
    when (this) {
        Visibility.PRIVATE -> "private"
        Visibility.SHARED -> "shared"
        Visibility.PUBLIC -> "public"
    }
