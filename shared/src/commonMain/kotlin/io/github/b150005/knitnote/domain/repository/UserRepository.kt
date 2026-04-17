package io.github.b150005.knitnote.domain.repository

import io.github.b150005.knitnote.domain.model.User

interface UserRepository {
    suspend fun getById(id: String): User?

    suspend fun getByIds(ids: List<String>): List<User>

    suspend fun searchByDisplayName(
        query: String,
        limit: Int = 10,
    ): List<User>

    suspend fun update(user: User): User
}
