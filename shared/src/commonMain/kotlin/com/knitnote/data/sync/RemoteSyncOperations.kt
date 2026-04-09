package com.knitnote.data.sync

import com.knitnote.domain.model.Progress
import com.knitnote.domain.model.Project

/**
 * Interface for project remote write operations needed by the sync system.
 */
interface RemoteProjectSyncOperations {
    suspend fun insert(project: Project): Project
    suspend fun update(project: Project): Project
    suspend fun delete(id: String)
}

/**
 * Interface for progress remote write operations needed by the sync system.
 */
interface RemoteProgressSyncOperations {
    suspend fun insert(progress: Progress): Progress
    suspend fun delete(id: String)
}
