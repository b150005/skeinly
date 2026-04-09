package com.knitnote.domain.repository

import com.knitnote.domain.model.User

interface UserRepository {
    suspend fun getById(id: String): User?
    suspend fun searchByDisplayName(query: String, limit: Int = 10): List<User>
}
