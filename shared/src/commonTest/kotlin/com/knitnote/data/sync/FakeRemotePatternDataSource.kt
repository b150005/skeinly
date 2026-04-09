package com.knitnote.data.sync

import com.knitnote.domain.model.Pattern

class FakeRemotePatternDataSource : RemotePatternSyncOperations {

    val insertedPatterns = mutableListOf<Pattern>()
    val updatedPatterns = mutableListOf<Pattern>()
    val deletedIds = mutableListOf<String>()
    var shouldFail = false

    override suspend fun insert(pattern: Pattern): Pattern {
        if (shouldFail) throw RuntimeException("Fake remote insert failure")
        insertedPatterns.add(pattern)
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
