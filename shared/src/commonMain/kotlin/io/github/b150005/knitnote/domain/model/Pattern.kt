package io.github.b150005.knitnote.domain.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
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

@OptIn(ExperimentalSerializationApi::class)
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
    // ADR-012 §1: set once at fork time (Phase 36.3); null for non-forked patterns.
    // @EncodeDefault(NEVER): keep the JSON wire-format from emitting `"parent_pattern_id": null`
    // on the sync-update path so a regular edit from a device whose local cache predates
    // migration 014 cannot silently overwrite the server-side attribution back to null.
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("parent_pattern_id") val parentPatternId: String? = null,
)
