package io.github.b150005.knitnote.domain.usecase

import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAuthRepository : AuthRepository {
    private val authStateFlow = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    var signInError: Throwable? = null
    var signUpError: Throwable? = null
    var signOutError: Throwable? = null
    var deleteAccountError: Throwable? = null
    private var currentUserId: String? = null

    override fun observeAuthState(): Flow<AuthState> = authStateFlow

    override suspend fun signInWithEmail(
        email: String,
        password: String,
    ) {
        signInError?.let { throw it }
        currentUserId = "test-user-id"
        authStateFlow.value = AuthState.Authenticated(userId = "test-user-id", email = email)
    }

    override suspend fun signUpWithEmail(
        email: String,
        password: String,
    ) {
        signUpError?.let { throw it }
        currentUserId = "new-user-id"
        authStateFlow.value = AuthState.Authenticated(userId = "new-user-id", email = email)
    }

    override suspend fun signOut() {
        signOutError?.let { throw it }
        currentUserId = null
        authStateFlow.value = AuthState.Unauthenticated
    }

    override suspend fun deleteAccount() {
        deleteAccountError?.let { throw it }
        currentUserId = null
        authStateFlow.value = AuthState.Unauthenticated
    }

    override fun getCurrentUserId(): String? = currentUserId

    fun setAuthState(state: AuthState) {
        authStateFlow.value = state
        currentUserId =
            when (state) {
                is AuthState.Authenticated -> state.userId
                else -> null
            }
    }
}
