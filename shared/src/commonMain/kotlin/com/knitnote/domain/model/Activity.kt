package com.knitnote.domain.model

import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ActivityType {
    @SerialName("shared") SHARED,
    @SerialName("commented") COMMENTED,
    @SerialName("forked") FORKED,
    @SerialName("completed") COMPLETED,
    @SerialName("started") STARTED,
}

@Serializable
enum class ActivityTargetType {
    @SerialName("pattern") PATTERN,
    @SerialName("project") PROJECT,
}

@Serializable
data class Activity(
    val id: String,
    @SerialName("user_id") val userId: String,
    val type: ActivityType,
    @SerialName("target_type") val targetType: ActivityTargetType,
    @SerialName("target_id") val targetId: String,
    val metadata: String?,
    @SerialName("created_at") val createdAt: Instant,
)
