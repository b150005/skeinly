package io.github.b150005.knitnote.data.repository

import io.github.b150005.knitnote.data.remote.RemoteUserDataSource
import io.github.b150005.knitnote.domain.model.User
import io.github.b150005.knitnote.domain.repository.UserRepository

class UserRepositoryImpl(
    private val remote: RemoteUserDataSource,
) : UserRepository {
    override suspend fun getById(id: String): User? = remote.getById(id)

    override suspend fun searchByDisplayName(
        query: String,
        limit: Int,
    ): List<User> = remote.searchByDisplayName(query, limit)

    override suspend fun getByIds(ids: List<String>): List<User> = remote.getByIds(ids)

    override suspend fun update(user: User): User = remote.update(user)
}
