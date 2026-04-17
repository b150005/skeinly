package io.github.b150005.knitnote.domain.model

import io.github.b150005.knitnote.domain.LocalUser
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
data class Progress(
    val id: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("row_number") val rowNumber: Int,
    @SerialName("photo_url") val photoUrl: String?,
    val note: String,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("owner_id") val ownerId: String = LocalUser.ID,
)
