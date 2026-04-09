package com.knitnote.domain.model

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CommentTargetType {
    @SerialName("pattern") PATTERN,
    @SerialName("project") PROJECT,
}

@Serializable
data class Comment(
    val id: String,
    @SerialName("author_id") val authorId: String,
    @SerialName("target_type") val targetType: CommentTargetType,
    @SerialName("target_id") val targetId: String,
    val body: String,
    @SerialName("created_at") val createdAt: Instant,
)
