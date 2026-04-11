package com.knitnote.data.sync

import com.knitnote.domain.model.Pattern

class FakeRemotePatternDataSource : RemotePatternSyncOperations {
    val upsertedPatterns = mutableListOf<Pattern>()
    val updatedPatterns = mutableListOf<Pattern>()
    val deletedIds = mutableListOf<String>()
    var shouldFail = false

    override suspend fun upsert(pattern: Pattern): Pattern {
        if (shouldFail) throw RuntimeException("Fake remote upsert failure")
        upsertedPatterns.add(pattern)
        return pattern
    }

    override suspend fun update(pattern: Pattern): Pattern {
        if (shouldFail) throw RuntimeException("Fake remote update failure")
        updatedPatterns.add(pattern)
        return pattern
    }

    override suspend fun delete(id: String) {
        if (shouldFail) throw RuntimeException("Fake remote delete failure")
        deletedIds.add(id)
    }
}
