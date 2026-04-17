package io.github.b150005.knitnote.data.repository

import io.github.b150005.knitnote.domain.model.User
import io.github.b150005.knitnote.domain.repository.UserRepository

/**
 * No-op fallback used when Supabase is not configured.
 * Returns empty/null results for reads; throws on writes.
 */
class OfflineUserRepository : UserRepository {
    override suspend fun getById(id: String): User? = null

    override suspend fun getByIds(ids: List<String>): List<User> = emptyList()

    override suspend fun searchByDisplayName(
        query: String,
        limit: Int,
    ): List<User> = emptyList()

    override suspend fun update(user: User): User = throw UnsupportedOperationException("User profile update requires cloud connectivity")
}
