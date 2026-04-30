package io.github.b150005.skeinly.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A named pointer to a tip revision within a chart's history (ADR-013 §7).
 * "main" is auto-created by [io.github.b150005.skeinly.domain.repository.StructuredChartRepository.create]
 * on first save and again by `forkFor` on a clone. UI for additional
 * branches lands in Phase 37.4.
 */
@Serializable
data class ChartBranch(
    val id: String,
    @SerialName("pattern_id") val patternId: String,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("branch_name") val branchName: String,
    @SerialName("tip_revision_id") val tipRevisionId: String,
    @SerialName("created_at") val createdAt: Instant,
    @SerialName("updated_at") val updatedAt: Instant,
) {
    companion object {
        const val DEFAULT_BRANCH_NAME: String = "main"
    }
}
