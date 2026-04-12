package com.knitnote.domain.usecase

import com.knitnote.domain.model.Share
import com.knitnote.domain.model.ShareStatus
import com.knitnote.domain.repository.ShareRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeShareRepository : ShareRepository {
    private val shares = MutableStateFlow<List<Share>>(emptyList())
    var closeChannelCalled = false

    override suspend fun closeChannel() {
        closeChannelCalled = true
    }

    override suspend fun getById(id: String): Share? = shares.value.find { it.id == id }

    override suspend fun getByPatternId(patternId: String): List<Share> = shares.value.filter { it.patternId == patternId }

    override suspend fun getByToken(token: String): Share? = shares.value.find { it.shareToken == token }

    override suspend fun getReceivedByUserId(userId: String): List<Share> = shares.value.filter { it.toUserId == userId }

    override fun observeReceivedByUserId(userId: String): Flow<List<Share>> = shares.map { list -> list.filter { it.toUserId == userId } }

    override suspend fun create(share: Share): Share {
        shares.value = shares.value + share
        return share
    }

    override suspend fun updateStatus(
        id: String,
        status: ShareStatus,
    ): Share {
        val updated =
            shares.value.find { it.id == id }?.copy(status = status)
                ?: error("Share not found: $id")
        shares.value = shares.value.map { if (it.id == id) updated else it }
        return updated
    }

    override suspend fun delete(id: String) {
        shares.value = shares.value.filter { it.id != id }
    }

    fun addShare(share: Share) {
        shares.value = shares.value + share
    }
}
