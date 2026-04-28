package io.github.b150005.knitnote.test

import io.github.b150005.knitnote.domain.model.AuthState
import io.github.b150005.knitnote.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeAuthRepository : AuthRepository {
    private val authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)

    override fun observeAuthState(): Flow<AuthState> = authState

    override suspend fun signInWithEmail(
        email: String,
        password: String,
    ) {
        authState.value = AuthState.Authenticated(userId = "test-user", email = email)
    }

    override suspend fun signUpWithEmail(
        email: String,
        password: String,
    ) {
        authState.value = AuthState.Authenticated(userId = "test-user", email = email)
    }

    override suspend fun signOut() {
        authState.value = AuthState.Unauthenticated
    }

    override fun getCurrentUserId(): String? {
        val state = authState.value
        return if (state is AuthState.Authenticated) state.userId else null
    }

    fun setAuthState(state: AuthState) {
        authState.value = state
    }
}
