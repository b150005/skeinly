package com.knitnote.domain.repository

import com.knitnote.domain.model.AuthState
import kotlinx.coroutines.flow.Flow

interface AuthRepository {
    fun observeAuthState(): Flow<AuthState>

    suspend fun signInWithEmail(
        email: String,
        password: String,
    )

    suspend fun signUpWithEmail(
        email: String,
        password: String,
    )

    suspend fun signOut()

    suspend fun deleteAccount()

    fun getCurrentUserId(): String?
}
