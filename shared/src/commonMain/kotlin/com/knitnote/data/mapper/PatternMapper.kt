package com.knitnote.data.mapper

import com.knitnote.db.PatternEntity
import com.knitnote.domain.model.Difficulty
import com.knitnote.domain.model.Pattern
import com.knitnote.domain.model.Visibility
import kotlin.time.Instant

fun PatternEntity.toDomain(): Pattern = Pattern(
    id = id,
    ownerId = owner_id,
    title = title,
    description = description,
    difficulty = difficulty?.toDifficulty(),
    gauge = null,
    yarnInfo = null,
    needleSize = null,
    chartImageUrls = emptyList(),
    visibility = visibility.toVisibility(),
    createdAt = Instant.parse(created_at),
    updatedAt = Instant.parse(updated_at),
)

private fun String.toDifficulty(): Difficulty = when (this) {
    "beginner" -> Difficulty.BEGINNER
    "intermediate" -> Difficulty.INTERMEDIATE
    "advanced" -> Difficulty.ADVANCED
    else -> throw IllegalStateException("Unknown Difficulty in database: '$this'")
}

private fun String.toVisibility(): Visibility = when (this) {
    "private" -> Visibility.PRIVATE
    "shared" -> Visibility.SHARED
    "public" -> Visibility.PUBLIC
    else -> throw IllegalStateException("Unknown Visibility in database: '$this'")
}

fun Difficulty.toDbString(): String = when (this) {
    Difficulty.BEGINNER -> "beginner"
    Difficulty.INTERMEDIATE -> "intermediate"
    Difficulty.ADVANCED -> "advanced"
}

fun Visibility.toDbString(): String = when (this) {
    Visibility.PRIVATE -> "private"
    Visibility.SHARED -> "shared"
    Visibility.PUBLIC -> "public"
}
