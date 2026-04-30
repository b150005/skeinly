package io.github.b150005.skeinly.data.sync

import io.github.b150005.skeinly.domain.model.ProjectSegment

class FakeRemoteProjectSegmentDataSource : RemoteProjectSegmentSyncOperations {
    val upsertedSegments = mutableListOf<ProjectSegment>()
    val deletedIds = mutableListOf<String>()
    var shouldFail = false

    override suspend fun upsert(segment: ProjectSegment): ProjectSegment {
        if (shouldFail) throw RuntimeException("Fake remote upsert failure")
        upsertedSegments.add(segment)
        return segment
    }

    override suspend fun delete(id: String) {
        if (shouldFail) throw RuntimeException("Fake remote delete failure")
        deletedIds.add(id)
    }
}
