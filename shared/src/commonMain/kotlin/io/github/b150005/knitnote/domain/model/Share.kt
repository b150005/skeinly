package io.github.b150005.knitnote.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
enum class SharePermission {
    @SerialName("view")
    VIEW,

    @SerialName("fork")
    FORK,
}

@Serializable
enum class ShareStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("accepted")
    ACCEPTED,

    @SerialName("declined")
    DECLINED,
}

@Serializable
data class Share(
    val id: String,
    @SerialName("pattern_id") val patternId: String,
    @SerialName("from_user_id") val fromUserId: String,
    @SerialName("to_user_id") val toUserId: String?,
    val permission: SharePermission,
    val status: ShareStatus,
    @SerialName("share_token") val shareToken: String?,
    @SerialName("shared_at") val sharedAt: Instant,
)
