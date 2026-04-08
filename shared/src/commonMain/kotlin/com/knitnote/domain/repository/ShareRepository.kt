package com.knitnote.domain.repository

import com.knitnote.domain.model.Share
import com.knitnote.domain.model.ShareStatus
import kotlinx.coroutines.flow.Flow

interface ShareRepository {
    suspend fun getById(id: String): Share?
    suspend fun getByPatternId(patternId: String): List<Share>
    suspend fun getByToken(token: String): Share?
    suspend fun getReceivedByUserId(userId: String): List<Share>
    fun observeReceivedByUserId(userId: String): Flow<List<Share>>
    suspend fun create(share: Share): Share
    suspend fun updateStatus(id: String, status: ShareStatus): Share
    suspend fun delete(id: String)
}
