package io.github.b150005.skeinly.domain.usecase

import io.github.b150005.skeinly.domain.model.ProjectSegment
import io.github.b150005.skeinly.domain.repository.ProjectSegmentRepository
import kotlinx.coroutines.flow.Flow

class ObserveProjectSegmentsUseCase(
    private val repository: ProjectSegmentRepository,
) {
    operator fun invoke(projectId: String): Flow<List<ProjectSegment>> = repository.observeByProjectId(projectId)
}
