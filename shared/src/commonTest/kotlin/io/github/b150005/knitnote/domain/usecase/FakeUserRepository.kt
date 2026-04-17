package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.User
import io.github.b150005.knitnote.domain.repository.UserRepository

class FakeUserRepository : UserRepository {
    private val users = mutableListOf<User>()

    override suspend fun getById(id: String): User? = users.find { it.id == id }

    override suspend fun getByIds(ids: List<String>): List<User> = users.filter { it.id in ids }

    override suspend fun searchByDisplayName(
        query: String,
        limit: Int,
    ): List<User> = users.filter { it.displayName.contains(query, ignoreCase = true) }.take(limit)

    override suspend fun update(user: User): User {
        val index = users.indexOfFirst { it.id == user.id }
        if (index >= 0) {
            users[index] = user
        } else {
            users.add(user)
        }
        return user
    }

    fun addUser(user: User) {
        users.add(user)
    }
}
