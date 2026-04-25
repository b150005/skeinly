package io.github.b150005.knitnote.data.mapper

import io.github.b150005.knitnote.db.ChartBranchEntity
import io.github.b150005.knitnote.domain.model.ChartBranch
import kotlin.time.Instant

internal fun ChartBranchEntity.toDomain(): ChartBranch =
    ChartBranch(
        id = id,
        patternId = pattern_id,
        ownerId = owner_id,
        branchName = branch_name,
        tipRevisionId = tip_revision_id,
        createdAt = Instant.parse(created_at),
        updatedAt = Instant.parse(updated_at),
    )
