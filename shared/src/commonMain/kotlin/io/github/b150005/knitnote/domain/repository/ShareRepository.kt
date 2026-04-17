package io.github.b150005.knitnote.domain.repository

import io.github.b150005.knitnote.domain.model.Share
import io.github.b150005.knitnote.domain.model.ShareStatus
import kotlinx.coroutines.flow.Flow

interface ShareRepository {
    suspend fun getById(id: String): Share?

    suspend fun getByPatternId(patternId: String): List<Share>

    suspend fun getByToken(token: String): Share?

    suspend fun getReceivedByUserId(userId: String): List<Share>

    fun observeReceivedByUserId(userId: String): Flow<List<Share>>

    suspend fun create(share: Share): Share

    suspend fun updateStatus(
        id: String,
        status: ShareStatus,
    ): Share

    suspend fun delete(id: String)

    /** Release Realtime subscription and clear cached state. Call on user logout. */
    suspend fun closeChannel() {}
}
