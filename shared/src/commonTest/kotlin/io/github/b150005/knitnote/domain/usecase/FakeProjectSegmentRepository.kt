package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.ProjectSegment
import io.github.b150005.knitnote.domain.repository.ProjectSegmentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeProjectSegmentRepository : ProjectSegmentRepository {
    private val segments = MutableStateFlow<Map<String, ProjectSegment>>(emptyMap())
    val resetProjectCalls = mutableListOf<String>()

    override fun observeByProjectId(projectId: String): Flow<List<ProjectSegment>> =
        segments.map { byId -> byId.values.filter { it.projectId == projectId }.sortedByDescending { it.updatedAt } }

    override suspend fun getById(id: String): ProjectSegment? = segments.value[id]

    override suspend fun getByProjectId(projectId: String): List<ProjectSegment> =
        segments.value.values.filter { it.projectId == projectId }

    override suspend fun upsert(segment: ProjectSegment): ProjectSegment {
        segments.value = segments.value + (segment.id to segment)
        return segment
    }

    override suspend fun resetSegment(id: String) {
        segments.value = segments.value - id
    }

    override suspend fun resetProject(projectId: String) {
        resetProjectCalls.add(projectId)
        segments.value = segments.value.filterValues { it.projectId != projectId }
    }

    fun seed(segment: ProjectSegment) {
        segments.value = segments.value + (segment.id to segment)
    }
}
