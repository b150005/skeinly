package io.github.b150005.knitnote.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
enum class Visibility {
    @SerialName("private")
    PRIVATE,

    @SerialName("shared")
    SHARED,

    @SerialName("public")
    PUBLIC,
}

@Serializable
enum class Difficulty {
    @SerialName("beginner")
    BEGINNER,

    @SerialName("intermediate")
    INTERMEDIATE,

    @SerialName("advanced")
    ADVANCED,
}

@Serializable
data class Pattern(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    val title: String,
    val description: String?,
    val difficulty: Difficulty?,
    val gauge: String?,
    @SerialName("yarn_info") val yarnInfo: String?,
    @SerialName("needle_size") val needleSize: String?,
    @SerialName("chart_image_urls") val chartImageUrls: List<String>,
    val visibility: Visibility,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
)
