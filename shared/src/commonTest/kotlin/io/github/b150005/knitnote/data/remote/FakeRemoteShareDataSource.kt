package io.github.b150005.knitnote.data.remote

import io.github.b150005.knitnote.domain.model.Share
import io.github.b150005.knitnote.domain.model.ShareStatus

/**
 * In-memory fake for [ShareDataSourceOperations] used in repository tests.
 */
class FakeRemoteShareDataSource : ShareDataSourceOperations {
    private val shares = mutableListOf<Share>()

    override suspend fun getById(id: String): Share? = shares.find { it.id == id }

    override suspend fun getByPatternId(patternId: String): List<Share> = shares.filter { it.patternId == patternId }

    override suspend fun getByToken(token: String): Share? = shares.find { it.shareToken == token }

    override suspend fun getReceivedByUserId(userId: String): List<Share> = shares.filter { it.toUserId == userId }

    override suspend fun insert(share: Share): Share {
        shares.add(share)
        return share
    }

    override suspend fun updateStatus(
        id: String,
        status: ShareStatus,
    ) {
        val index = shares.indexOfFirst { it.id == id }
        if (index >= 0) {
            shares[index] = shares[index].copy(status = status)
        }
    }

    override suspend fun delete(id: String) {
        shares.removeAll { it.id == id }
    }

    /** Test helper: pre-populate shares. */
    fun addShare(share: Share) {
        shares.add(share)
    }
}
