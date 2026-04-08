package com.knitnote.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ProjectStatus {
    @SerialName("not_started") NOT_STARTED,
    @SerialName("in_progress") IN_PROGRESS,
    @SerialName("completed") COMPLETED,
}

@Serializable
data class Project(
    val id: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("pattern_id") val patternId: String,
    val title: String,
    val status: ProjectStatus,
    @SerialName("current_row") val currentRow: Int,
    @SerialName("total_rows") val totalRows: Int?,
    @SerialName("started_at") val startedAt: Instant?,
    @SerialName("completed_at") val completedAt: Instant?,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
)
