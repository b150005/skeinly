package com.knitnote.domain.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Progress(
    val id: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("row_number") val rowNumber: Int,
    @SerialName("photo_url") val photoUrl: String?,
    val note: String,
    @SerialName("created_at") val createdAt: Instant,
)
