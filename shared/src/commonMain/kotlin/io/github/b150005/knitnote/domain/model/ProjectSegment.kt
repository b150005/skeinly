package io.github.b150005.knitnote.domain.model

import io.github.b150005.knitnote.domain.LocalUser
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

@Serializable
enum class SegmentState {
    @SerialName("wip")
    WIP,

    @SerialName("done")
    DONE,
}

/**
 * Per-project per-stitch progress row. Absence of a row means the segment
 * is in the implicit `todo` state (see ADR-010 §2).
 *
 * The [id] format `seg:<projectId>:<layerId>:<cellX>:<cellY>` is deterministic
 * so rapid double-taps collapse under the existing
 * `(entity_type, entity_id)` PendingSync coalescing without a DB round-trip
 * (ADR-010 §3). The id is never user-visible.
 */
@Serializable
data class ProjectSegment(
    val id: String,
    @SerialName("project_id") val projectId: String,
    @SerialName("layer_id") val layerId: String,
    @SerialName("cell_x") val cellX: Int,
    @SerialName("cell_y") val cellY: Int,
    val state: SegmentState,
    @SerialName("owner_id") val ownerId: String = LocalUser.ID,
    @SerialName("updated_at") val updatedAt: Instant,
) {
    companion object {
        fun buildId(
            projectId: String,
            layerId: String,
            cellX: Int,
            cellY: Int,
        ): String = "seg:$projectId:$layerId:$cellX:$cellY"
    }
}
