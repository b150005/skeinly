package io.github.b150005.skeinly.domain.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Phase 25.1 (ADR-024 §(b)) — 4-value visibility enum. Migration 035
 * extended the SQL CHECK constraint to enforce this exact set.
 *
 * - [PRIVATE]: owner-only. The default for new patterns at the DB
 *   level; the PatternEditScreen picker's UI-level initial state
 *   for NEW patterns is [PUBLIC] per ADR-024 §(c).
 * - [FRIENDS]: owner + accepted-friend-graph members (Phase 25.1
 *   `is_friend()` SQL function). NEW in Phase 25.1.
 * - [SHARED]: anyone with a valid share token URL (link-share, via
 *   the existing `shares` table). Orthogonal to [FRIENDS] — both
 *   are distinct trust models, not chained.
 * - [PUBLIC]: visible in the Discovery feed.
 */
@Serializable
enum class Visibility {
    @SerialName("private")
    PRIVATE,

    @SerialName("friends")
    FRIENDS,

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
