package com.knitnote.data.repository

import com.knitnote.data.remote.RemoteShareDataSource
import com.knitnote.domain.model.Share
import com.knitnote.domain.model.ShareStatus
import com.knitnote.domain.repository.ShareRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Remote-only Share repository. Sharing is inherently online — no local SQLDelight cache.
 * Operations require network connectivity; callers should handle failures gracefully.
 */
class ShareRepositoryImpl(
    private val remote: RemoteShareDataSource,
) : ShareRepository {

    override suspend fun getById(id: String): Share? =
        remote.getById(id)

    override suspend fun getByPatternId(patternId: String): List<Share> =
        remote.getByPatternId(patternId)

    override suspend fun getByToken(token: String): Share? =
        remote.getByToken(token)

    override suspend fun getReceivedByUserId(userId: String): List<Share> =
        remote.getReceivedByUserId(userId)

    override fun observeReceivedByUserId(userId: String): Flow<List<Share>> = flow {
        while (true) {
            emit(remote.getReceivedByUserId(userId))
            delay(POLLING_INTERVAL_MS)
        }
    }

    override suspend fun create(share: Share): Share =
        remote.insert(share)

    override suspend fun updateStatus(id: String, status: ShareStatus): Share {
        remote.updateStatus(id, status)
        return remote.getById(id) ?: error("Share not found after status update: $id")
    }

    override suspend fun delete(id: String) =
        remote.delete(id)

    companion object {
        private const val POLLING_INTERVAL_MS = 30_000L
    }
}
