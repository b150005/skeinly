package io.github.b150005.skeinly.data.mapper

import io.github.b150005.skeinly.db.ChartVariationEntity
import io.github.b150005.skeinly.domain.model.ChartVariation
import kotlin.time.Instant

internal fun ChartVariationEntity.toDomain(): ChartVariation =
    ChartVariation(
        id = id,
        patternId = pattern_id,
        ownerId = owner_id,
        branchName = branch_name,
        tipRevisionId = tip_revision_id,
        createdAt = Instant.parse(created_at),
        updatedAt = Instant.parse(updated_at),
    )
