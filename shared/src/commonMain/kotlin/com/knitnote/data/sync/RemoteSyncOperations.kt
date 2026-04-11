package com.knitnote.data.sync

import com.knitnote.domain.model.Pattern
import com.knitnote.domain.model.Progress
import com.knitnote.domain.model.Project

/**
 * Interface for project remote write operations needed by the sync system.
 */
interface RemoteProjectSyncOperations {
    suspend fun upsert(project: Project): Project

    suspend fun update(project: Project): Project

    suspend fun delete(id: String)
}

/**
 * Interface for progress remote write operations needed by the sync system.
 */
interface RemoteProgressSyncOperations {
    suspend fun upsert(progress: Progress): Progress

    suspend fun delete(id: String)
}

/**
 * Interface for pattern remote write operations needed by the sync system.
 */
interface RemotePatternSyncOperations {
    suspend fun upsert(pattern: Pattern): Pattern

    suspend fun update(pattern: Pattern): Pattern

    suspend fun delete(id: String)
}
