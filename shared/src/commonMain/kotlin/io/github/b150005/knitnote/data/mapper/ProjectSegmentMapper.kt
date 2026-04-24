package io.github.b150005.knitnote.data.mapper

import io.github.b150005.knitnote.db.ProjectSegmentEntity
import io.github.b150005.knitnote.domain.model.ProjectSegment
import io.github.b150005.knitnote.domain.model.SegmentState
import kotlin.time.Instant

fun ProjectSegmentEntity.toDomain(): ProjectSegment =
    ProjectSegment(
        id = id,
        projectId = project_id,
        layerId = layer_id,
        cellX = cell_x.toInt(),
        cellY = cell_y.toInt(),
        state = state.toSegmentState(),
        ownerId = owner_id,
        updatedAt = Instant.parse(updated_at),
    )

fun SegmentState.toDbString(): String =
    when (this) {
        SegmentState.WIP -> "wip"
        SegmentState.DONE -> "done"
    }

private fun String.toSegmentState(): SegmentState =
    when (this) {
        "wip" -> SegmentState.WIP
        "done" -> SegmentState.DONE
        else -> error("Unknown SegmentState wire value: '$this'")
    }
