package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.ProjectSegment
import io.github.b150005.knitnote.domain.repository.ProjectSegmentRepository
import kotlinx.coroutines.flow.Flow

class ObserveProjectSegmentsUseCase(
    private val repository: ProjectSegmentRepository,
) {
    operator fun invoke(projectId: String): Flow<List<ProjectSegment>> = repository.observeByProjectId(projectId)
}
