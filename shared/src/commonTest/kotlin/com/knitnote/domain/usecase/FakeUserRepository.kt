package com.knitnote.domain.usecase

import com.knitnote.domain.model.User
import com.knitnote.domain.repository.UserRepository

class FakeUserRepository : UserRepository {

    private val users = mutableListOf<User>()

    override suspend fun getById(id: String): User? =
        users.find { it.id == id }

    override suspend fun searchByDisplayName(query: String, limit: Int): List<User> =
        users.filter { it.displayName.contains(query, ignoreCase = true) }.take(limit)

    fun addUser(user: User) {
        users.add(user)
    }
}
